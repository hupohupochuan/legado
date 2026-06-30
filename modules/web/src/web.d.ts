export type webReadConfig = {
  theme: number
  font: number
  fontSize: number
  readWidth: number
  infiniteLoading: boolean
  customFontName: string
  jumpDuration: number
  spacing: {
    paragraph: number
    line: number
    letter: number
  }
  /** 阅读模式: 'scroll' 连续滚动 (默认), 'book' 书本翻页 */
  pageMode: 'scroll' | 'book'
  /** 书本翻页下的翻页效果: 'book' CSS 3D 翻页 (默认), 'slide' 滑动 */
  pageTurnEffect: 'slide' | 'book'
}