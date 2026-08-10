package com.hzzmonet.zkbomb.core

import android.content.Context
import com.hzzmonet.zkbomb.api.AutomationRuleParcel
import com.hzzmonet.zkbomb.domain.automation.AutomationRule
import org.json.JSONArray
import org.json.JSONObject

interface AutomationRuleRepository {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    fun rules(): List<AutomationRule>
    fun upsert(rule: AutomationRule): Boolean
    fun delete(ruleId: String): Boolean
}

class InMemoryAutomationRuleRepository(
    enabled: Boolean = false,
    initialRules: Collection<AutomationRule> = emptyList(),
) : AutomationRuleRepository {
    private var enabled = enabled
    private val rules = LinkedHashMap<String, AutomationRule>().apply {
        initialRules.forEach { put(it.id, it) }
    }

    @Synchronized override fun isEnabled(): Boolean = enabled

    @Synchronized override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    @Synchronized override fun rules(): List<AutomationRule> = rules.values.toList()

    @Synchronized override fun upsert(rule: AutomationRule): Boolean {
        if (rule.id !in rules && rules.size >= MAX_RULES) return false
        rules[rule.id] = rule
        return true
    }

    @Synchronized override fun delete(ruleId: String): Boolean = rules.remove(ruleId) != null

    companion object { const val MAX_RULES = 256 }
}

/** Small SharedPreferences store; corrupt entries fail closed one rule at a time. */
class SharedPreferencesAutomationRuleRepository(context: Context) : AutomationRuleRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun rules(): List<AutomationRule> {
        val raw = preferences.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), InMemoryAutomationRuleRepository.MAX_RULES)) {
                    decode(array.optJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun upsert(rule: AutomationRule): Boolean {
        val updated = LinkedHashMap<String, AutomationRule>()
        rules().forEach { updated[it.id] = it }
        if (rule.id !in updated && updated.size >= InMemoryAutomationRuleRepository.MAX_RULES) {
            return false
        }
        updated[rule.id] = rule
        persist(updated.values)
        return true
    }

    @Synchronized
    override fun delete(ruleId: String): Boolean {
        val updated = LinkedHashMap<String, AutomationRule>()
        rules().forEach { updated[it.id] = it }
        if (updated.remove(ruleId) == null) return false
        persist(updated.values)
        return true
    }

    private fun persist(rules: Collection<AutomationRule>) {
        val array = JSONArray()
        rules.forEach { rule -> array.put(encode(AutomationRuleParcel.fromDomain(rule))) }
        preferences.edit().putString(KEY_RULES, array.toString()).apply()
    }

    private fun encode(rule: AutomationRuleParcel): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("enabled", rule.enabled)
        put("trigger", rule.trigger)
        put("triggerPackageName", rule.triggerPackageName ?: JSONObject.NULL)
        put("scope", rule.scope)
        put("priority", rule.priority)
        put("cooldownMillis", rule.cooldownMillis)
        put("debounceMillis", rule.debounceMillis)
        put("restorePolicy", rule.restorePolicy)
        put("conditions", JSONArray().apply {
            rule.conditions.forEach { condition ->
                put(JSONObject().put("type", condition.type).put("value", condition.value))
            }
        })
        put("actions", JSONArray().apply {
            rule.actions.forEach { action ->
                put(
                    JSONObject()
                        .put("type", action.type)
                        .put("value", action.value)
                        .put("packageName", action.packageName ?: JSONObject.NULL)
                        .put("userId", action.userId),
                )
            }
        })
    }

    private fun decode(value: JSONObject?): AutomationRule? {
        value ?: return null
        return runCatching {
            val conditionsJson = value.getJSONArray("conditions")
            val actionsJson = value.getJSONArray("actions")
            AutomationRuleParcel(
                id = value.getString("id"),
                name = value.getString("name"),
                enabled = value.getBoolean("enabled"),
                trigger = value.getString("trigger"),
                triggerPackageName = value.nullableString("triggerPackageName"),
                conditions = buildList {
                    for (index in 0 until conditionsJson.length()) {
                        val condition = conditionsJson.getJSONObject(index)
                        add(
                            com.hzzmonet.zkbomb.api.AutomationConditionParcel(
                                condition.getString("type"),
                                condition.getString("value"),
                            ),
                        )
                    }
                },
                actions = buildList {
                    for (index in 0 until actionsJson.length()) {
                        val action = actionsJson.getJSONObject(index)
                        add(
                            com.hzzmonet.zkbomb.api.AutomationActionParcel(
                                type = action.getString("type"),
                                value = action.getString("value"),
                                packageName = action.nullableString("packageName"),
                                userId = action.getInt("userId"),
                            ),
                        )
                    }
                },
                scope = value.getString("scope"),
                priority = value.getInt("priority"),
                cooldownMillis = value.getLong("cooldownMillis"),
                debounceMillis = value.getLong("debounceMillis"),
                restorePolicy = value.getString("restorePolicy"),
            ).toDomain()
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES = "bomb_automation_rules"
        const val KEY_ENABLED = "enabled"
        const val KEY_RULES = "rules_json"
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
}
