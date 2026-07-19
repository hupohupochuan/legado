import sourceEditor from '../views/SourceEditor.vue'
import replaceRuleEditor from '../views/ReplaceRuleEditor.vue'

export const sourceRoutes = [
  {
    path: '/bookSource',
    name: 'book-home',
    component: sourceEditor,
    meta: { title: '书源' },
  },
  {
    path: '/replaceRule',
    name: 'replace-rule',
    component: replaceRuleEditor,
    meta: { title: '替换规则' },
  },
]
