export const bookRoutes = [
  {
    path: '/shelf',
    name: 'shelf',
    component: () => import('../views/BookShelf.vue'),
    meta: { title: '书架' },
  },
  {
    path: '/chapter',
    name: 'chapter',
    component: () => import('../views/BookChapter.vue'),
    // 阅读页标题由 BookChapter 挂载后按「书名 | 章节名」自行设置
  },
  {
    path: '/uploadBook',
    name: 'upload-book',
    component: () => import('../views/UploadBook.vue'),
    meta: { title: '网页传书' },
  },
]
