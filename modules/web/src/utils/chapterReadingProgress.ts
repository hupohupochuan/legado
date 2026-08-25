export type ChapterReadingProgress = {
  currentChapter: number
  totalChapters: number
  percentage: number
  percentageText: string
  label: string
}

const finiteIntegerOrZero = (value: number) => {
  return Number.isFinite(value) ? Math.trunc(value) : 0
}

/**
 * 按当前章节计算整本书进度。
 *
 * `chapterIndex` 与手机 Room 中的 `durChapterIndex` 保持相同的 0-based
 * 语义；展示时转为 1-based 章节号。这里故意不混入 chapterPos、页数或
 * 排版信息，保证手机与 Web 在同一章节时显示完全相同的总进度。
 */
export const getChapterReadingProgress = (
  chapterIndex: number,
  totalChapters: number,
): ChapterReadingProgress => {
  const safeTotal = Math.max(0, finiteIntegerOrZero(totalChapters))
  if (safeTotal === 0) {
    return {
      currentChapter: 0,
      totalChapters: 0,
      percentage: 0,
      percentageText: '0.0%',
      label: '总进度 0.0%　0/0',
    }
  }

  const safeIndex = finiteIntegerOrZero(chapterIndex)
  const currentChapter = Math.min(Math.max(safeIndex, 0), safeTotal - 1) + 1
  const percentage = (currentChapter / safeTotal) * 100
  const percentageText = `${percentage.toFixed(1)}%`

  return {
    currentChapter,
    totalChapters: safeTotal,
    percentage,
    percentageText,
    label: `总进度 ${percentageText}　${currentChapter}/${safeTotal}`,
  }
}
