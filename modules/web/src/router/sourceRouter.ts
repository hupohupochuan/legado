import sourceEditor from '../views/SourceEditor.vue'
import replaceRuleEditor from '../views/ReplaceRuleEditor.vue'
import { createWebHashHistory, createRouter } from 'vue-router'

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

const router = createRouter({
  history: createWebHashHistory(),
  routes: sourceRoutes,
})

export default router
