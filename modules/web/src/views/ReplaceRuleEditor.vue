<template>
  <div class="editor">
    <div class="left">
      <div class="panel-header">
        <input
          class="web-input"
          v-model="searchKey"
          placeholder="筛选规则"
        />
        <button class="web-btn web-btn--primary" @click="newRule">新建</button>
      </div>
      <div class="rule-list">
        <div
          v-for="rule in filteredRules"
          :key="rule.id"
          class="rule-item"
          :class="{ active: rule.id === store.currentRuleId }"
          @click="selectRule(rule)"
        >
          <span class="rule-name">{{ rule.name || '未命名规则' }}</span>
          <span v-if="rule.group" class="rule-group">{{ rule.group }}</span>
        </div>
        <div v-if="filteredRules.length === 0" class="web-empty">暂无规则</div>
      </div>
    </div>

    <div class="center">
      <div class="form-toolbar">
        <button class="web-btn web-btn--primary" @click="saveRule">保存</button>
        <button class="web-btn web-btn--danger" @click="deleteRule">删除</button>
      </div>
      <div class="form-body">
        <div class="form-row">
          <div class="form-col">
            <div class="web-form-group">
              <label class="web-form-label">
                名称 <span class="required">*</span>
              </label>
              <input class="web-input" v-model="store.currentRule.name" />
            </div>
          </div>
          <div class="form-col">
            <div class="web-form-group">
              <label class="web-form-label">分组</label>
              <input class="web-input" v-model="store.currentRule.group" />
            </div>
          </div>
        </div>

        <div class="web-form-group">
          <label class="web-form-label">
            替换内容/正则 <span class="required">*</span>
          </label>
          <textarea
            class="web-textarea"
            v-model="store.currentRule.pattern"
            rows="3"
          ></textarea>
        </div>

        <div class="web-form-group">
          <label class="web-form-label">替换为</label>
          <textarea
            class="web-textarea"
            v-model="store.currentRule.replacement"
            rows="2"
          ></textarea>
        </div>

        <div class="form-row">
          <div class="form-col">
            <div class="web-form-group">
              <label class="web-form-label">作用范围</label>
              <input
                class="web-input"
                v-model="store.currentRule.scope"
                placeholder="为空则全局生效"
              />
            </div>
          </div>
          <div class="form-col">
            <div class="web-form-group">
              <label class="web-form-label">排除范围</label>
              <input class="web-input" v-model="store.currentRule.excludeScope" />
            </div>
          </div>
        </div>

        <div class="form-row form-row--checkbox">
          <label class="web-checkbox">
            <input type="checkbox" v-model="store.currentRule.scopeTitle" />
            作用于标题
          </label>
          <label class="web-checkbox">
            <input type="checkbox" v-model="store.currentRule.scopeContent" />
            作用于正文
          </label>
          <label class="web-checkbox">
            <input type="checkbox" v-model="store.currentRule.isEnabled" />
            启用
          </label>
          <label class="web-checkbox">
            <input type="checkbox" v-model="store.currentRule.isRegex" />
            正则
          </label>
        </div>

        <div class="form-row">
          <div class="form-col">
            <div class="web-form-group">
              <label class="web-form-label">超时（毫秒）</label>
              <input
                class="web-input"
                type="number"
                v-model.number="store.currentRule.timeoutMillisecond"
              />
            </div>
          </div>
          <div class="form-col">
            <div class="web-form-group">
              <label class="web-form-label">排序</label>
              <input
                class="web-input"
                type="number"
                v-model.number="store.currentRule.order"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="right">
      <div class="panel-title">测试替换</div>
      <div class="web-form-group">
        <label class="web-form-label">待测文本</label>
        <textarea
          class="web-textarea"
          v-model="testText"
          rows="6"
        ></textarea>
      </div>
      <button
        class="web-btn web-btn--primary test-btn"
        :disabled="testing"
        @click="runTest"
      >
        {{ testing ? '测试中' : '测试' }}
      </button>
      <div class="web-form-group">
        <label class="web-form-label">结果</label>
        <textarea
          class="web-textarea result-text"
          v-model="testResult"
          rows="8"
          readonly
        ></textarea>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import '@/assets/sourceeditor.css'
import API from '@api'
import { useReplaceRuleStore } from '@/store'
import { toast, msgbox } from '@/utils/toast'
import type { ReplaceRule } from '@/replaceRule'

const store = useReplaceRuleStore()
const searchKey = ref('')
const testText = ref('')
const testResult = ref('')
const testing = ref(false)

const filteredRules = computed(() => {
  const key = searchKey.value.trim().toLowerCase()
  if (!key) return store.rules
  return store.rules.filter(
    rule =>
      rule.name.toLowerCase().includes(key) ||
      (rule.group ?? '').toLowerCase().includes(key) ||
      rule.pattern.toLowerCase().includes(key),
  )
})

const selectRule = (rule: ReplaceRule) => {
  store.changeCurrentRule(rule)
}

const newRule = () => {
  store.newRule()
}

const saveRule = async () => {
  const rule = store.currentRule
  if (!rule.name.trim() || !rule.pattern.trim()) {
    toast.error('名称和替换内容不能为空')
    return
  }
  try {
    await store.saveRule()
    toast.success('保存成功')
  } catch (e: any) {
    toast.error(e.message || '保存失败')
  }
}

const deleteRule = async () => {
  const rule = store.currentRule
  if (store.rules.findIndex(r => r.id === rule.id) === -1) {
    toast.info('请先选择要删除的规则')
    return
  }
  try {
    await msgbox.confirm('确认删除该替换规则？', '提示', { type: 'warning' })
    await store.deleteRule(rule)
    store.newRule()
    toast.success('删除成功')
  } catch {
    // 取消
  }
}

const runTest = async () => {
  if (!store.currentRule.pattern.trim()) {
    toast.error('替换内容不能为空')
    return
  }
  testing.value = true
  try {
    const { data } = await API.testReplaceRule(store.currentRule, testText.value)
    if (data.isSuccess) {
      testResult.value = data.data ?? ''
    } else {
      toast.error(data.errorMsg)
    }
  } catch (e: any) {
    toast.error(e.message || '测试失败')
  } finally {
    testing.value = false
  }
}

onMounted(() => {
  document.title = '替换规则'
  store.loadRules().catch((e: any) => toast.error(e.message || '加载失败'))
})
</script>

<style lang="scss" scoped>
.editor {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.left {
  flex: 0 0 260px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--web-border);
  padding: 12px;
  min-width: 0;
}

.panel-header {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.rule-list {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.rule-item {
  display: flex;
  flex-direction: column;
  padding: 10px 12px;
  border: 1px solid var(--web-border);
  border-radius: var(--web-radius);
  margin-bottom: 8px;
  cursor: pointer;
  transition: var(--web-transition);
}

.rule-item:hover,
.rule-item.active {
  border-color: var(--web-primary);
  background: var(--web-primary-light);
}

.rule-name {
  font-size: 14px;
  color: var(--web-text);
  word-break: break-all;
}

.rule-group {
  font-size: 12px;
  color: var(--web-text-secondary);
  margin-top: 4px;
}

.center {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 12px 16px;
  min-width: 0;
  overflow: hidden;
}

.form-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.form-body {
  flex: 1;
  overflow-y: auto;
  padding-right: 8px;
  min-height: 0;
}

.form-row {
  display: flex;
  gap: 16px;
}

.form-col {
  flex: 1;
  min-width: 0;
}

.form-row--checkbox {
  margin-bottom: 18px;
  align-items: center;
}

.right {
  flex: 0 0 320px;
  display: flex;
  flex-direction: column;
  padding: 12px 16px;
  border-left: 1px solid var(--web-border);
  min-width: 0;
}

.panel-title {
  font-size: 16px;
  font-weight: 500;
  margin-bottom: 12px;
  color: var(--web-text);
}

.test-btn {
  margin-bottom: 12px;
}

.result-text {
  background: var(--web-bg);
}
</style>
