import { createWebHashHistory, createRouter } from 'vue-router'
import { bookRoutes } from './bookRouter'
import { sourceRoutes } from './sourceRouter'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'welcome',
      component: () => import('../views/Welcome.vue'),
      meta: { title: 'Legado Web' },
    },
    ...bookRoutes,
    ...sourceRoutes,
  ].flat(),
})

router.afterEach(to => {
  // 无 meta.title 的路由（如阅读页）保持当前标题不变
  const title = to.meta.title
  if (typeof title === 'string') document.title = title
})

export default router
