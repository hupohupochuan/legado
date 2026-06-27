import { defineStore } from 'pinia'
import API from '@api'
import type { ReplaceRule } from '@/replaceRule'

let _idCounter = 0
export const emptyReplaceRule = (): ReplaceRule => ({
  id: Date.now() * 1000 + (++_idCounter % 1000),
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
        await this.loadRules()
        const saved = this.rules.find(r => r.id === target.id)
        if (saved) this.currentRule = JSON.parse(JSON.stringify(saved))
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
