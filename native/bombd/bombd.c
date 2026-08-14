#define _GNU_SOURCE

#include <errno.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

#define MAX_REQUEST 96
#define APP_UID_MIN 10000

/*
 * The peer's SELinux context, not its process name.
 *
 * This used to read /proc/<pid>/cmdline and compare it to the expected process
 * name. That could not work under this daemon's own policy: procfs pid inodes
 * are labelled with the *task's* context, not `proc`, so reading a bomb_app
 * process's cmdline needed `allow bombd bomb_app:file read` — which is not
 * granted, and deliberately so. Every command would have been refused with
 * "ERR peer", leaving an AVC denial as the only clue.
 *
 * SO_PEERSEC answers the same question directly and better. It is captured by
 * the kernel at connect time, so unlike the SO_PEERCRED pid it cannot be
 * recycled underneath us between the check and the use.
 */
#define EXPECTED_CONTEXT_PREFIX "u:r:bomb_app:s0"

/* Neither reading nor writing may block the accept loop. See handle_client. */
#define IO_TIMEOUT_SECONDS 2

extern int __system_property_set(const char *name, const char *value);

static void log_error(const char *message) {
    (void)write(STDERR_FILENO, "bombd: ", 7);
    (void)write(STDERR_FILENO, message, strlen(message));
    (void)write(STDERR_FILENO, "\n", 1);
}

static bool parse_int(const char *text, int minimum, int maximum, int *result) {
    if (text == NULL || *text == '\0') return false;
    errno = 0;
    char *end = NULL;
    long value = strtol(text, &end, 10);
    if (errno != 0 || *end != '\0' || value < minimum || value > maximum) return false;
    *result = (int)value;
    return true;
}

/*
 * The peer runs in the bomb_app domain.
 *
 * The kernel may or may not NUL-terminate the returned context, so the compare
 * is bounded by the returned length rather than trusting the buffer.
 */
static bool peer_context_matches(int client) {
    char context[128] = {0};
    socklen_t size = sizeof(context) - 1;
    if (getsockopt(client, SOL_SOCKET, SO_PEERSEC, context, &size) != 0) return false;
    if (size == 0 || size >= sizeof(context)) return false;
    context[size] = '\0';
    /* Trim a trailing NUL the kernel counted in the length. */
    size_t length = strnlen(context, size);
    const size_t prefix_length = strlen(EXPECTED_CONTEXT_PREFIX);
    /*
     * seapp_contexts assigns Bomb with levelFrom=all, so Android app processes
     * normally arrive as u:r:bomb_app:s0:cNNN,cMMM. The MLS categories are
     * UID-derived isolation labels, not a different SELinux domain. Requiring
     * the literal category-free string rejects the real app on every normal
     * install and makes even PING fail with ERR peer.
     *
     * Keep the domain check exact and accept only either the end of the context
     * or the ':' that begins the MLS category suffix. A type such as
     * bomb_app_helper therefore cannot pass this prefix check.
     */
    return length >= prefix_length &&
           memcmp(context, EXPECTED_CONTEXT_PREFIX, prefix_length) == 0 &&
           (length == prefix_length || context[prefix_length] == ':');
}

static bool authenticated_peer(int client) {
    struct ucred credential = {0};
    socklen_t size = sizeof(credential);
    if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &credential, &size) != 0 ||
        size != sizeof(credential)) return false;
    /*
     * Both checks, not either. The UID bound rejects a system-side caller that
     * somehow reached the socket; the context check is the one that actually
     * identifies Bomb, and it is the one SELinux itself would enforce on
     * connectto. Keeping both means a policy mistake on one side does not
     * silently become the only gate.
     */
    return credential.uid >= APP_UID_MIN && peer_context_matches(client);
}

/* A peer that connects and never speaks must not be able to stall the loop. */
static bool set_io_timeouts(int client) {
    struct timeval timeout = {.tv_sec = IO_TIMEOUT_SECONDS, .tv_usec = 0};
    return setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0 &&
           setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) == 0;
}

static bool set_property(const char *name, const char *value) {
    return __system_property_set(name, value) == 0;
}

static bool apply_log(char *level, char *audit_text) {
    if (level == NULL || audit_text == NULL) return false;
    if (strcmp(level, "default") != 0 && strcmp(level, "reduced") != 0 &&
        strcmp(level, "off") != 0) return false;
    int audit_rate = -1;
    if (!parse_int(audit_text, -1, 1000, &audit_rate)) return false;
    char audit_value[12] = {0};
    /*
     * bomb_control_prop declares this property as an integer. An empty string
     * (the old wire handling for -1) is rejected by Android's property type
     * checker, so the following request property was never written and every
     * default UI call failed. The target init profile uses 5 when the property
     * is absent; materialise the same bounded default here.
     */
    snprintf(audit_value, sizeof(audit_value), "%d", audit_rate >= 0 ? audit_rate : 5);
    return set_property("persist.sys.bomb.log.audit_rate", audit_value) &&
           set_property("persist.sys.bomb.log.request", level);
}

static bool apply_memory(char *field, char *value_text) {
    if (field == NULL || value_text == NULL) return false;
    int value = 0;
    const char *property = NULL;
    if (strcmp(field, "swappiness") == 0 && parse_int(value_text, 0, 200, &value)) {
        property = "persist.sys.bomb.memory.swappiness";
    } else if (strcmp(field, "page_cluster") == 0 && parse_int(value_text, 0, 6, &value)) {
        property = "persist.sys.bomb.memory.page_cluster";
    } else {
        return false;
    }
    char value_buffer[12];
    snprintf(value_buffer, sizeof(value_buffer), "%d", value);
    return set_property(property, value_buffer);
}

/*
 * Charge control. init owns the actual sysfs write (see bomb.rc); bombd merely
 * publishes the range-checked request property. The fields and bounds match the
 * app-side RomControlPropertyWriter and PowerSupplyBackend:
 *   current_max       microamps, 100000..20000000
 *   end_threshold     percent, 0..100
 *   disable           charge-disable gate, 0/1
 *   charging_enabled  charging-enabled gate, 0/1
 *   input_suspend     input-suspend gate, 0/1
 */
static bool apply_charge(char *field, char *value_text) {
    if (field == NULL || value_text == NULL) return false;
    const char *property = NULL;
    int minimum = 0;
    int maximum = 0;
    if (strcmp(field, "current_max") == 0) {
        property = "persist.sys.bomb.charge.current_max";
        minimum = 100000;
        maximum = 20000000;
    } else if (strcmp(field, "end_threshold") == 0) {
        property = "persist.sys.bomb.charge.end_threshold";
        minimum = 0;
        maximum = 100;
    } else if (strcmp(field, "disable") == 0) {
        property = "persist.sys.bomb.charge.disable";
        minimum = 0;
        maximum = 1;
    } else if (strcmp(field, "charging_enabled") == 0) {
        property = "persist.sys.bomb.charge.charging_enabled";
        minimum = 0;
        maximum = 1;
    } else if (strcmp(field, "input_suspend") == 0) {
        property = "persist.sys.bomb.charge.input_suspend";
        minimum = 0;
        maximum = 1;
    } else {
        return false;
    }
    int value = 0;
    if (!parse_int(value_text, minimum, maximum, &value)) return false;
    char value_buffer[12];
    snprintf(value_buffer, sizeof(value_buffer), "%d", value);
    return set_property(property, value_buffer);
}

/* Frequency values can exceed INT_MAX (GPU Hz approaches 2-3e9), so the freq
 * handler parses a 64-bit value rather than the int used elsewhere. */
static bool parse_llong(const char *text, long long minimum, long long maximum,
                        long long *result) {
    if (text == NULL || *text == '\0') return false;
    errno = 0;
    char *end = NULL;
    long long value = strtoll(text, &end, 10);
    if (errno != 0 || *end != '\0' || value < minimum || value > maximum) return false;
    *result = value;
    return true;
}

/* cpuN_min / cpuN_max, policy index N in 0..7. The strict charset also makes the
 * field safe to interpolate into the request property name below. */
static bool valid_cpu_freq_field(const char *field) {
    if (strncmp(field, "cpu", 3) != 0) return false;
    if (field[3] < '0' || field[3] > '7') return false;
    const char *suffix = field + 4;
    return strcmp(suffix, "_min") == 0 || strcmp(suffix, "_max") == 0;
}

/*
 * CPU/GPU frequency limits. As with charge control, init owns the sysfs write
 * (bomb.rc) and bombd only publishes the range-checked request property. Fields
 * mirror the app-side RomControlPropertyWriter and FrequencyScalingBackend:
 *   cpu0_min..cpu7_max  per-policy scaling_min/max_freq, kHz, 100000..50000000
 *   gpu_min / gpu_max   GPU devfreq/kgsl min/max, Hz, 10000000..3000000000
 */
static bool apply_freq(char *field, char *value_text) {
    if (field == NULL || value_text == NULL) return false;
    long long minimum = 0;
    long long maximum = 0;
    if (valid_cpu_freq_field(field)) {
        minimum = 100000LL;
        maximum = 50000000LL;
    } else if (strcmp(field, "gpu_min") == 0 || strcmp(field, "gpu_max") == 0) {
        minimum = 10000000LL;
        maximum = 3000000000LL;
    } else {
        return false;
    }
    long long value = 0;
    if (!parse_llong(value_text, minimum, maximum, &value)) return false;
    char property[64];
    if (snprintf(property, sizeof(property), "persist.sys.bomb.freq.%s", field) >=
        (int)sizeof(property)) {
        return false;
    }
    char value_buffer[24];
    snprintf(value_buffer, sizeof(value_buffer), "%lld", value);
    return set_property(property, value_buffer);
}

static bool dispatch(char *request) {
    char *save = NULL;
    char *verb = strtok_r(request, " ", &save);
    if (verb == NULL) return false;
    if (strcmp(verb, "PING") == 0) return strtok_r(NULL, " ", &save) == NULL;
    char *argument = strtok_r(NULL, " ", &save);
    char *value = strtok_r(NULL, " ", &save);
    if (strtok_r(NULL, " ", &save) != NULL) return false;
    if (strcmp(verb, "LOG") == 0) return apply_log(argument, value);
    if (strcmp(verb, "MEM") == 0) return apply_memory(argument, value);
    if (strcmp(verb, "CHARGE") == 0) return apply_charge(argument, value);
    if (strcmp(verb, "FREQ") == 0) return apply_freq(argument, value);
    return false;
}

static void handle_client(int client) {
    /*
     * Timeouts first. Everything below writes to the client, and a peer that
     * never reads would otherwise block the accept loop on the reply just as
     * surely as one that never writes.
     */
    if (!set_io_timeouts(client)) {
        log_error("cannot set socket timeouts");
        return;
    }
    if (!authenticated_peer(client)) {
        (void)write(client, "ERR peer\n", 9);
        return;
    }
    char request[MAX_REQUEST] = {0};
    ssize_t count = read(client, request, sizeof(request) - 1);
    if (count <= 0) {
        /* Includes EAGAIN from the receive timeout: a silent peer is dropped. */
        (void)write(client, "ERR read\n", 9);
        return;
    }
    char *newline = memchr(request, '\n', (size_t)count);
    if (newline == NULL || newline != request + count - 1) {
        (void)write(client, "ERR framing\n", 12);
        return;
    }
    *newline = '\0';
    const bool success = dispatch(request);
    (void)write(client, success ? "OK\n" : "ERR request\n", success ? 3 : 12);
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    const char *socket_text = getenv("ANDROID_SOCKET_bombd");
    int server = -1;
    if (!parse_int(socket_text, 0, 1024, &server) || listen(server, 8) != 0) {
        log_error("missing or invalid init control socket");
        return EXIT_FAILURE;
    }
    for (;;) {
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            log_error("accept failed");
            return EXIT_FAILURE;
        }
        handle_client(client);
        close(client);
    }
}
