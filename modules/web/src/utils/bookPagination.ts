import type { webReadConfig } from '@/web'

/**
 * 书本翻页分页模型与 DOM 测量分页算法。
 *
 * 与滚动模式 `ChapterContent.vue` 共享 `chapterPos` 语义（按段落累计字符数），
 * 因此切换模式后进度可互转，不会丢失阅读位置。
 *
 * `chapterPos` 语义参考 `ChapterContent.vue`:
 * - 标题段固定为 0
 * - 第 i 段 topPos = sum_{j<=i}(wc_j + 1) - 1，wc_j 为该段去除图片标签后的字符数
 */

export type PageBlock =
  | { type: 'title'; text: string; topPos: number }
  | {
      type: 'paragraph'
      html: string
      topPos: number
    }
  | { type: 'image'; html: string; src: string; topPos: number }

export type BookPage = {
  /** 页内第一个块的 topPos，作为翻到本页时保存的 chapterPos */
  startPos: number
  /** 页内最后一个块的 topPos，用于按 chapterPos 反查页 */
  endPos: number
  blocks: PageBlock[]
}

const imgFullPattern = /^\s*<img[^>]*src[^>]+>$/
const imgSrcPattern = /<img[^>]*src=['"]([^'"]*(?:['"][^>]+\})?)['"][^>]*>/
const imgTagPattern = /<img[^>]*src=['"][^'"]*(?:['"][^>]+\})?['"][^>]*>/g

const wordCountOf = (paragraph: string) => {
  // 与 ChapterContent.vue calculateWordCount 保持一致：内嵌图片算 1 字符
  return paragraph.replace(imgTagPattern, ' ').length
}

export type PaginationInput = {
  contents: string[]
  title: string
  spacing: webReadConfig['spacing']
  fontFamily: string
  fontSize: string
}

/**
 * 预先把章节内容拆成块并计算每块的 topPos，复用滚动模式 chapterPos 公式。
 */
export function buildBlocks(input: PaginationInput): PageBlock[] {
  const blocks: PageBlock[] = [{ type: 'title', text: input.title, topPos: 0 }]
  let pos = -1
  for (const para of input.contents) {
    const wc = wordCountOf(para)
    pos += wc + 1
    if (imgFullPattern.test(String(para))) {
      const src = String(para).match(imgSrcPattern)?.[1] ?? ''
      blocks.push({ type: 'image', html: String(para), src, topPos: pos })
    } else {
      blocks.push({ type: 'paragraph', html: String(para), topPos: pos })
    }
  }
  return blocks
}

/**
 * 在给定测量函数（将块渲染进同尺寸容器并返回是否溢出）下把整章块切成页面。
 *
 * 该函数不直接触碰 DOM；组件侧传入测量回调，组件用一个隐藏容器完成实际测量，
 * 这里只负责调度与切分逻辑。
 */
export function paginateBlocks(
  blocks: PageBlock[],
  measure: {
    /** 追加块到当前页测量容器，返回是否溢出（已追加则需由 rollback 撤回） */
    append: (block: PageBlock) => boolean
    /** 回滚最后一次 append */
    rollback: () => void
    /** 清空测量容器，准备开始新一页 */
    reset: () => void
    /** 段落块单独一页都放不下时按字符切分，每个 chunk 应能单独放入一页 */
    splitParagraph: (
      block: Extract<PageBlock, { type: 'paragraph' }>,
    ) => PageBlock[]
  },
): BookPage[] {
  const pages: BookPage[] = []
  let current: PageBlock[] = []

  const flush = () => {
    if (current.length === 0) return
    const startPos = current[0].topPos
    const endPos = current[current.length - 1].topPos
    pages.push({ startPos, endPos, blocks: current })
    current = []
    measure.reset()
  }

  const fits = (block: PageBlock) => {
    if (measure.append(block)) {
      measure.rollback()
      return false
    }
    return true
  }

  const place = (block: PageBlock) => {
    if (fits(block)) {
      current.push(block)
      return
    }
    // 当前页已有内容则先换页
    if (current.length > 0) flush()
    // 空页单独尝试
    if (fits(block)) {
      current.push(block)
      return
    }
    // 仍放不下：段落切分；标题/图片强制独占
    if (block.type === 'paragraph') {
      const chunks = measure.splitParagraph(block)
      for (const chunk of chunks) place(chunk)
    } else {
      current.push(block)
      flush()
    }
  }

  measure.reset()
  for (const block of blocks) place(block)
  flush()
  return pages
}

/** 根据旧 chapterPos 找到第一个 endPos >= pos 的页索引 */
export function findPageIndexByPos(pages: BookPage[], pos: number): number {
  for (let i = 0; i < pages.length; i++) {
    if (pages[i].endPos >= pos) return i
  }
  return Math.max(0, pages.length - 1)
}