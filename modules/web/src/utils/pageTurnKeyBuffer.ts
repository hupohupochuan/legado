export type PageTurnDirection = 'next' | 'prev'

type PageTurnHandler = (direction: PageTurnDirection) => void

/**
 * 动画锁期间只保留最后一次键盘翻页意图，解锁后执行一次。
 * 这样快速连续按键不会丢失跨章动作，也不会累积成多次切章。
 */
export const createPageTurnKeyBuffer = (turn: PageTurnHandler) => {
  let pendingDirection: PageTurnDirection | null = null

  const request = (direction: PageTurnDirection, locked: boolean) => {
    if (locked) {
      pendingDirection = direction
      return false
    }
    pendingDirection = null
    turn(direction)
    return true
  }

  const flush = () => {
    const direction = pendingDirection
    pendingDirection = null
    if (!direction) return false
    turn(direction)
    return true
  }

  const clear = () => {
    pendingDirection = null
  }

  return { request, flush, clear }
}
