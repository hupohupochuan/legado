import sourceEditor from '../views/SourceEditor.vue'
import replaceRuleEditor from '../views/ReplaceRuleEditor.vue'

export const sourceRoutes = [
  {
    path: '/bookSource',
    name: 'book-home',
    component: sourceEditor,
  },
  {
    path: '/replaceRule',
    name: 'replace-rule',
    component: replaceRuleEditor,
  },
]
