import { defineStore } from 'pinia'
import API from '@api'
import type { ReplaceRule } from '@/replaceRule'

export const emptyReplaceRule = (): ReplaceRule => ({
  id: Date.now(),
  name: '',
  group: '',
  pattern: '',
  replacement: '',
  scope: '',
  scopeTitle: false,
  scopeContent: true,
  excludeScope: '',
  isEnabled: true,
  isRegex: true,
  timeoutMillisecond: 3000,
  order: -2147483648,
})

export const useReplaceRuleStore = defineStore('replaceRule', {
  state: () => {
    return {
      rules: [] as ReplaceRule[],
      currentRule: emptyReplaceRule(),
    }
  },
  getters: {
    currentRuleId: state => state.currentRule.id,
    rulesFiltered: state => {
      return (key: string) => {
        if (!key) return state.rules
        const lowerKey = key.toLowerCase()
        return state.rules.filter(
          rule =>
            rule.name.toLowerCase().includes(lowerKey) ||
            (rule.group ?? '').toLowerCase().includes(lowerKey) ||
            rule.pattern.toLowerCase().includes(lowerKey),
        )
      }
    },
  },
  actions: {
    setRules(data: ReplaceRule[]) {
      this.rules = data
    },
    changeCurrentRule(rule: ReplaceRule) {
      this.currentRule = JSON.parse(JSON.stringify(rule))
    },
    newRule() {
      this.currentRule = emptyReplaceRule()
    },
    saveCurrentRuleToList() {
      const index = this.rules.findIndex(rule => rule.id === this.currentRule.id)
      if (index > -1) {
        this.rules[index] = JSON.parse(JSON.stringify(this.currentRule))
      } else {
        this.rules.push(JSON.parse(JSON.stringify(this.currentRule)))
      }
    },
    deleteRuleFromList(rule: ReplaceRule) {
      const index = this.rules.findIndex(item => item.id === rule.id)
      if (index > -1) this.rules.splice(index, 1)
    },
    async loadRules() {
      const { data } = await API.getReplaceRules()
      if (data.isSuccess) {
        this.setRules(data.data)
      } else {
        throw new Error(data.errorMsg)
      }
    },
    async saveRule(rule?: ReplaceRule) {
      const target = rule ?? this.currentRule
      const { data } = await API.saveReplaceRule(target)
      if (data.isSuccess) {
        this.saveCurrentRuleToList()
      } else {
        throw new Error(data.errorMsg)
      }
    },
    async deleteRule(rule: ReplaceRule) {
      const { data } = await API.deleteReplaceRule(rule)
      if (data.isSuccess) {
        this.deleteRuleFromList(rule)
      } else {
        throw new Error(data.errorMsg)
      }
    },
  },
})
