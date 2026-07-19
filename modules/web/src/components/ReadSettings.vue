<template>
  <div class="settings-popup">
    <div class="setting-row">
      <span>阅读主题</span>
      <div class="theme-dots">
        <div
          v-for="(color, i) in themes"
          :key="i"
          class="theme-dot"
          :class="{ active: i === store.config.theme }"
          :style="{ background: color }"
          @click="setTheme(i)"
        ></div>
      </div>
    </div>
    <div class="setting-row">
      <span>正文字体</span>
      <div class="font-options">
        <span
          v-for="(font, i) in fontNames"
          :key="i"
          class="font-opt"
          :class="{ active: i === store.config.font }"
          @click="setFont(i)"
        >{{ fontDisplay[i] }}</span>
      </div>
    </div>
    <div class="setting-row">
      <span>字体大小</span>
      <div class="size-control">
        <button class="size-btn" @click="changeFontSize(-1)">−</button>
        <span class="size-val">{{ store.config.fontSize }}</span>
        <button class="size-btn" @click="changeFontSize(1)">+</button>
      </div>
    </div>
    <div class="setting-row">
      <span>阅读宽度</span>
      <input class="web-slider" type="range" min="640" max="1200" :value="store.config.readWidth" @input="setReadWidth(Number(($event.target as HTMLInputElement).value))" />
      <span style="font-size:12px;min-width:40px">{{ store.config.readWidth }}</span>
    </div>
    <div class="setting-row">
      <span>字距</span>
      <div class="size-control">
        <button class="size-btn" @click="changeSpacing('letter', -0.05)">−</button>
        <span class="size-val">{{ store.config.spacing.letter.toFixed(2) }}</span>
        <button class="size-btn" @click="changeSpacing('letter', 0.05)">+</button>
      </div>
    </div>
    <div class="setting-row">
      <span>行距</span>
      <div class="size-control">
        <button class="size-btn" @click="changeSpacing('line', -0.1)">−</button>
        <span class="size-val">{{ store.config.spacing.line.toFixed(1) }}</span>
        <button class="size-btn" @click="changeSpacing('line', 0.1)">+</button>
      </div>
    </div>
    <div class="setting-row">
      <span>段距</span>
      <div class="size-control">
        <button class="size-btn" @click="changeSpacing('paragraph', -0.1)">−</button>
        <span class="size-val">{{ store.config.spacing.paragraph.toFixed(1) }}</span>
        <button class="size-btn" @click="changeSpacing('paragraph', 0.1)">+</button>
      </div>
    </div>
    <div class="setting-row">
      <span>阅读模式</span>
      <div class="font-options">
        <span
          class="font-opt"
          :class="{ active: activePageMode === 'scroll' }"
          @click="setPageMode('scroll')"
        >滚动</span>
        <span
          class="font-opt"
          :class="{ active: activePageMode === 'book' }"
          @click="setPageMode('book')"
        >书本翻页</span>
      </div>
    </div>
    <div class="setting-row" :class="{ 'setting-row--disabled': activePageMode !== 'book' }">
      <span>翻页效果</span>
      <div class="font-options">
        <span
          class="font-opt"
          :class="{ active: store.config.pageTurnEffect === 'book' }"
          @click="setPageTurnEffect('book')"
        >纸质书</span>
        <span
          class="font-opt"
          :class="{ active: store.config.pageTurnEffect === 'slide' }"
          @click="setPageTurnEffect('slide')"
        >滑动</span>
      </div>
    </div>
    <div class="setting-row" :class="{ 'setting-row--disabled': activePageMode === 'book' }">
      <span>无限滚动</span>
      <label class="web-switch">
        <input
          type="checkbox"
          :checked="store.config.infiniteLoading"
          :disabled="activePageMode === 'book'"
          @change="toggleInfinite"
        />
        <span class="web-switch__slider"></span>
      </label>
    </div>
    <div class="web-form-group">
      <label class="web-form-label">自定义字体</label>
      <input class="web-input" placeholder="输入字体名称" :value="store.config.customFontName" @input="setCustomFont(($event.target as HTMLInputElement).value)" />
    </div>
  </div>
</template>

<script setup lang="ts">
import API from '@api'
import settings from '@/config/themeConfig'

const store = useBookStore()
const themes = settings.themes.map(t => t.content)
const fontNames = settings.fonts
const fontDisplay = ['默认', '宋体', '楷体']

function setTheme(i: number) {
  store.config.theme = i
  API.saveReadConfig(store.config)
}
function setFont(i: number) {
  store.config.font = i
  API.saveReadConfig(store.config)
}
function changeFontSize(d: number) {
  store.config.fontSize = Math.max(10, Math.min(40, store.config.fontSize + d))
  API.saveReadConfig(store.config)
}
function setReadWidth(w: number) {
  store.config.readWidth = w
  API.saveReadConfig(store.config)
}
function changeSpacing(key: keyof typeof store.config.spacing, d: number) {
  store.config.spacing[key] = parseFloat(Math.max(0, Math.min(5, (store.config.spacing[key] || 0) + d)).toFixed(2))
  API.saveReadConfig(store.config)
}
function toggleInfinite(e: Event) {
  if (store.activePageMode === 'book') return
  store.config.infiniteLoading = (e.target as HTMLInputElement).checked
  API.saveReadConfig(store.config)
}
const activePageMode = computed(() => store.activePageMode)
function setPageMode(mode: 'scroll' | 'book') {
  if (store.config.pageMode === mode) {
    // 用户再次点当前模式时，确保运行时态同步
    store.syncActivePageMode()
    return
  }
  store.config.pageMode = mode
  // 书本模式下逻辑层强制不启用无限滚动，避免组件怪态
  if (mode === 'book') store.config.infiniteLoading = false
  store.syncActivePageMode()
  API.saveReadConfig(store.config)
}
function setPageTurnEffect(effect: 'slide' | 'book') {
  store.config.pageTurnEffect = effect
  API.saveReadConfig(store.config)
}
function setCustomFont(v: string) {
  store.config.customFontName = v
  API.saveReadConfig(store.config)
}
</script>

<style scoped>
.settings-popup { display: flex; flex-direction: column; gap: 14px; padding: 4px 0; }
.setting-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.setting-row > span:first-child { font-size: 14px; color: var(--web-text-secondary); flex-shrink: 0; width: 64px; text-align: right; }
.theme-dots { display: flex; gap: 6px; }
.theme-dot { width: 24px; height: 24px; border-radius: 50%; cursor: pointer; border: 2px solid transparent; transition: all .2s; }
.theme-dot:hover { transform: scale(1.15); }
.theme-dot.active { border-color: var(--web-primary); box-shadow: 0 0 0 2px var(--web-primary); }
.font-options { display: flex; gap: 4px; }
.font-opt { padding: 4px 10px; border-radius: 4px; cursor: pointer; font-size: 13px; border: 1px solid var(--web-border); transition: all .2s; }
.font-opt:hover { border-color: var(--web-primary); }
.font-opt.active { background: var(--web-primary); color: #fff; border-color: var(--web-primary); }
.size-control { display: flex; align-items: center; gap: 6px; }
.size-btn { width: 26px; height: 26px; border-radius: 50%; border: 1px solid var(--web-border); background: var(--web-bg-white); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 16px; color: var(--web-text); }
.size-btn:hover { border-color: var(--web-primary); color: var(--web-primary); }
.size-val { min-width: 32px; text-align: center; font-weight: 600; font-size: 14px; }
.setting-row--disabled { opacity: .45; pointer-events: none; }
</style>
