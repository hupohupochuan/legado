import type { LegadoApiResponse } from './api'
import API, {
  setWebsocketOnError,
  setApiEntryPoint,
  legado_http_entry_point,
  setWebsocketOnMessage,
} from './api'
import ajax, { type AjaxResponse } from './axios'
import { validatorHttpUrl } from '@/utils/utils'
import { toast } from '@/utils/toast'

import { setActivePinia } from 'pinia'
import store, { useConnectionStore } from '@/store'

// 注册 active pinia 供模块级 useConnectionStore() 使用，
// 不再为此额外 createApp 一个未挂载的 Vue 应用实例
setActivePinia(store)
const connectionStore = useConnectionStore()

const legadoApiResponseKeys: string[] = Array.of('isSuccess', 'errorMsg')
export const backendConnectionErrorMessage = '网络异常，与手机断开联系'
const backendConnectionErrorKey = '__legadoBackendConnectionError'

export const isBackendConnectionError = (err: unknown) => {
  return typeof err === 'object' && err !== null && (err as Record<string, unknown>)[backendConnectionErrorKey] === true
}

const markBackendConnectionError = (err: unknown) => {
  if (typeof err === 'object' && err !== null) {
    try {
      Object.defineProperty(err, backendConnectionErrorKey, {
        value: true,
        configurable: true,
      })
    } catch {
      ;(err as Record<string, unknown>)[backendConnectionErrorKey] = true
    }
  }
}

/** Interceptor: check if resp is LegadoApiResponse*/
const responseCheckInterceptor = (resp: AjaxResponse) => {
  let isLegadoApiResponse = true
  const data: unknown = resp.data
  if (typeof data !== 'object' || data === null) {
    isLegadoApiResponse = false
  } else {
    for (const key of legadoApiResponseKeys) {
      if (!(key in data)) {
        isLegadoApiResponse = false
        break
      }
    }
    if ((data as LegadoApiResponse<unknown>).isSuccess === true) {
      if (!('data' in data)) {
        isLegadoApiResponse = false
      }
    }
  }
  if (isLegadoApiResponse === false) {
    toast.warning({ message: '后端返回内容格式错误', grouping: true })
    throw new Error()
  }
  connectionStore.setConnectType('primary')
  connectionStore.setConnectStatus('已连接 ' + legado_http_entry_point)
  return resp
}

const fetchErrorInterceptor = (err: unknown) => {
  markBackendConnectionError(err)
  toast.error({
    message: backendConnectionErrorMessage,
    grouping: true,
  })
  connectionStore.setConnectType('danger')
  connectionStore.setConnectStatus('连接异常')
  throw err
}
// http全局
ajax.interceptors.response.use(responseCheckInterceptor, fetchErrorInterceptor)
// websocket
setWebsocketOnError(fetchErrorInterceptor)
setWebsocketOnMessage(() => {
  connectionStore.setConnectType('primary')
  connectionStore.setConnectStatus('已连接 ' + legado_http_entry_point)
})
/**
 * 按照阅读的默认规则 解析阅读HTTP WebSocket API入口地址
 */
export const parseLegadoHttpUrlWithDefault = (
  http_url: string | URL,
): [string, string] => {
  let url = new URL(location.origin)
  if (validatorHttpUrl(http_url)) {
    url = new URL(http_url)
  }
  const { protocol, port } = url
  let legado_webSocket_port
  if (port !== '') {
    legado_webSocket_port = String(Number(port) + 1)
  } else {
    legado_webSocket_port = protocol.startsWith('https:') ? '444' : '81'
  }
  const legado_webSocket_protocol = protocol.startsWith('https:')
    ? 'wss://'
    : 'ws://'

  const http_entry_point = url.toString()

  url.protocol = legado_webSocket_protocol
  url.port = legado_webSocket_port
  const webSocket_entry_point = url.toString()

  console.info('legado_api_config:')
  console.table({
    'http API入口': http_entry_point,
    'webSocket API入口': webSocket_entry_point,
  })
  return [http_entry_point, webSocket_entry_point]
}

setApiEntryPoint(
  ...parseLegadoHttpUrlWithDefault(ajax.defaults.baseURL as string),
)

export default API
export * from './api'
