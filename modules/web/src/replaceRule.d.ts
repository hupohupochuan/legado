/**
 * 对应后端 io.legado.app.data.entities.ReplaceRule
 */
type ReplaceRule = {
  /**
   * 主键，后端默认使用 System.currentTimeMillis()
   */
  id: number
  /**
   * 名称
   */
  name: string
  /**
   * 分组
   */
  group?: string
  /**
   * 替换内容（正则或普通文本）
   */
  pattern: string
  /**
   * 替换为
   */
  replacement: string
  /**
   * 作用范围
   */
  scope?: string
  /**
   * 作用于标题
   */
  scopeTitle: boolean
  /**
   * 作用于正文
   */
  scopeContent: boolean
  /**
   * 排除范围
   */
  excludeScope?: string
  /**
   * 是否启用
   */
  isEnabled: boolean
  /**
   * 是否正则
   */
  isRegex: boolean
  /**
   * 超时时间（毫秒）
   */
  timeoutMillisecond: number
  /**
   * 排序，新增时传 -2147483648 后端会自动分配
   */
  order: number
}

export { ReplaceRule }
