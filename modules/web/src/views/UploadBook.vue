<template>
  <main class="upload-page">
    <header class="upload-header">
      <div>
        <p class="upload-eyebrow">Legado Web</p>
        <h1>网页传书</h1>
        <p class="upload-description">
          文件会依次上传并导入手机书架；失败时显示手机端返回的真实原因。
        </p>
      </div>
      <nav class="upload-nav">
        <button class="web-btn" @click="goHome">返回首页</button>
        <button class="web-btn" @click="goShelf">打开书架</button>
      </nav>
    </header>

    <section
      class="drop-zone"
      :class="{ 'drop-zone--active': isDragging }"
      @dragenter.prevent="isDragging = true"
      @dragover.prevent="isDragging = true"
      @dragleave.prevent="onDragLeave"
      @drop.prevent="onDrop"
    >
      <input
        ref="fileInput"
        class="file-input"
        type="file"
        multiple
        :accept="LOCAL_BOOK_ACCEPT"
        @change="onFileSelected"
      />
      <button
        class="web-btn web-btn--primary web-btn--large"
        @click="selectFiles"
      >
        选择书籍文件
      </button>
      <p>也可以把多个文件拖到这里</p>
      <p class="supported-types">
        支持
        {{ LOCAL_BOOK_EXTENSIONS.map(item => item.toUpperCase()).join('、') }}
      </p>
    </section>

    <section class="upload-list" aria-live="polite">
      <div class="upload-list-header">
        <h2>上传列表</h2>
        <button
          v-if="
            items.some(
              item => item.status === 'success' || item.status === 'error',
            )
          "
          class="web-btn web-btn--text"
          @click="clearFinished"
        >
          清除已完成
        </button>
      </div>

      <div v-if="items.length === 0" class="empty-list">尚未选择文件</div>
      <div v-else class="table-wrapper">
        <table>
          <thead>
            <tr>
              <th>文件名</th>
              <th>大小</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td class="file-name" :title="item.file.name">
                {{ item.file.name }}
              </td>
              <td>{{ formatSize(item.file.size) }}</td>
              <td>
                <div class="status-cell">
                  <span :class="['status', `status--${item.status}`]">
                    {{ statusText(item) }}
                  </span>
                  <progress
                    v-if="item.status === 'uploading'"
                    :value="item.progress"
                    max="100"
                  ></progress>
                </div>
              </td>
              <td>
                <button
                  v-if="item.status === 'error'"
                  class="web-btn web-btn--text"
                  @click="retry(item)"
                >
                  重试
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import API from '@api'
import { toast } from '@/utils/toast'
import {
  LOCAL_BOOK_ACCEPT,
  LOCAL_BOOK_EXTENSIONS,
  isSupportedLocalBook,
} from '@/utils/localBookUpload'

type UploadStatus = 'queued' | 'uploading' | 'success' | 'error'

type UploadItem = {
  id: number
  file: File
  status: UploadStatus
  progress: number
  message: string
}

const router = useRouter()
const fileInput = ref<HTMLInputElement>()
const items = ref<UploadItem[]>([])
const isDragging = ref(false)
const isUploading = ref(false)
let nextId = 0

const goHome = () => router.push({ path: '/' })
const goShelf = () => router.push({ path: '/shelf' })
const selectFiles = () => fileInput.value?.click()

const formatSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(2)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

const statusText = (item: UploadItem) => {
  if (item.status === 'queued') return '等待上传'
  if (item.status === 'uploading') return `上传中 ${item.progress}%`
  if (item.status === 'success') return '已导入手机书架'
  return item.message || '上传失败'
}

const enqueueFiles = (files: File[]) => {
  const rejected: string[] = []
  const knownNames = new Set(items.value.map(item => item.file.name))

  files.forEach(file => {
    let reason = ''
    if (!isSupportedLocalBook(file.name)) reason = '格式不支持'
    else if (file.size === 0) reason = '文件为空'
    else if (knownNames.has(file.name)) reason = '文件名重复'

    if (reason) {
      rejected.push(`${file.name}：${reason}`)
      return
    }

    knownNames.add(file.name)
    items.value.push({
      id: ++nextId,
      file,
      status: 'queued',
      progress: 0,
      message: '',
    })
  })

  if (rejected.length > 0) {
    toast.warning({
      message: rejected.join('；'),
      duration: 5000,
    })
  }
  void uploadQueuedFiles()
}

const onFileSelected = (event: Event) => {
  const input = event.target as HTMLInputElement
  enqueueFiles(Array.from(input.files ?? []))
  input.value = ''
}

const onDragLeave = (event: DragEvent) => {
  const nextTarget = event.relatedTarget
  if (!(nextTarget instanceof Node) || !event.currentTarget) {
    isDragging.value = false
    return
  }
  if (!(event.currentTarget as HTMLElement).contains(nextTarget)) {
    isDragging.value = false
  }
}

const onDrop = (event: DragEvent) => {
  isDragging.value = false
  enqueueFiles(Array.from(event.dataTransfer?.files ?? []))
}

const uploadQueuedFiles = async () => {
  if (isUploading.value) return
  isUploading.value = true
  try {
    while (true) {
      const item = items.value.find(candidate => candidate.status === 'queued')
      if (!item) break

      item.status = 'uploading'
      item.progress = 0
      item.message = ''
      try {
        const { data } = await API.addLocalBook(item.file, (loaded, total) => {
          item.progress = Math.min(100, Math.round((loaded / total) * 100))
        })
        if (data.isSuccess) {
          item.status = 'success'
          item.progress = 100
        } else {
          item.status = 'error'
          item.message = data.errorMsg || '手机端导入失败'
        }
      } catch (error) {
        item.status = 'error'
        item.message =
          error instanceof Error && error.message
            ? error.message
            : '网络异常，与手机断开联系'
      }
    }
  } finally {
    isUploading.value = false
  }
}

const retry = (item: UploadItem) => {
  item.status = 'queued'
  item.progress = 0
  item.message = ''
  void uploadQueuedFiles()
}

const clearFinished = () => {
  items.value = items.value.filter(
    item => item.status === 'queued' || item.status === 'uploading',
  )
}
</script>

<style lang="scss" scoped>
.upload-page {
  min-height: 100vh;
  padding: 40px clamp(16px, 5vw, 72px);
  background: var(--web-bg);
  color: var(--web-text);
}

.upload-header {
  max-width: 1080px;
  margin: 0 auto 28px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;

  h1 {
    margin: 4px 0 8px;
    font-size: 32px;
  }
}

.upload-eyebrow,
.upload-description,
.supported-types {
  margin: 0;
  color: var(--web-text-secondary);
}

.upload-eyebrow {
  font-size: 13px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.upload-nav {
  display: flex;
  gap: 10px;
}

.drop-zone,
.upload-list {
  max-width: 1080px;
  margin: 0 auto;
  border: 1px solid var(--web-border);
  border-radius: var(--web-radius-lg);
  background: var(--web-bg-white);
  box-shadow: var(--web-shadow-light);
}

.drop-zone {
  min-height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 32px;
  border-style: dashed;
  transition: var(--web-transition);

  p {
    margin: 0;
  }
}

.drop-zone--active {
  border-color: var(--web-primary);
  background: var(--web-primary-light);
  transform: translateY(-2px);
}

.file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.supported-types {
  max-width: 720px;
  text-align: center;
  font-size: 13px;
}

.upload-list {
  margin-top: 24px;
  overflow: hidden;
}

.upload-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px;
  border-bottom: 1px solid var(--web-border-light);

  h2 {
    margin: 0;
    font-size: 18px;
  }
}

.empty-list {
  padding: 48px 24px;
  text-align: center;
  color: var(--web-text-placeholder);
}

.table-wrapper {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

th,
td {
  padding: 14px 18px;
  border-bottom: 1px solid var(--web-border-light);
  text-align: left;
  vertical-align: middle;
}

th {
  color: var(--web-text-secondary);
  font-size: 13px;
  font-weight: 500;
}

th:nth-child(1) {
  width: 38%;
}

th:nth-child(2) {
  width: 14%;
}

th:nth-child(3) {
  width: 38%;
}

th:nth-child(4) {
  width: 10%;
}

.file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-cell {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.status {
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.status--queued {
  color: var(--web-info);
}

.status--uploading {
  color: var(--web-primary);
}

.status--success {
  color: var(--web-success);
}

.status--error {
  color: var(--web-danger);
}

progress {
  width: 100%;
  height: 6px;
  accent-color: var(--web-primary);
}

@media (max-width: 700px) {
  .upload-page {
    padding-top: 24px;
  }

  .upload-header {
    align-items: stretch;
    flex-direction: column;
  }

  .upload-nav {
    .web-btn {
      flex: 1;
    }
  }

  .drop-zone {
    min-height: 180px;
    padding: 24px 16px;
  }

  th,
  td {
    padding: 12px;
  }

  th:nth-child(1) {
    width: 180px;
  }

  th:nth-child(2) {
    width: 90px;
  }

  th:nth-child(3) {
    width: 220px;
  }

  th:nth-child(4) {
    width: 70px;
  }
}
</style>
