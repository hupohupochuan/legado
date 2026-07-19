/** @type {string} localStorage保存自定义阅读http服务接口的键值 */
export const baseURL_localStorage_key = 'remoteUrl'

const baseURL =
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API) ||
  (typeof localStorage !== 'undefined' ? localStorage.getItem(baseURL_localStorage_key) : null) ||
  (typeof location !== 'undefined' ? location.origin : '')

interface FetchConfig {
  baseURL?: string
}

export interface AjaxRequest {
  url: string
  options?: RequestInit
}

export interface AjaxResponse<T = unknown> {
  data: T
  status: number
  headers: Headers
  config: AjaxRequest
}

interface RequestInterceptor {
  onFulfilled?: (
    config: AjaxRequest,
  ) => AjaxRequest | void | Promise<AjaxRequest | void>
  onRejected?: (error: unknown) => unknown
}

interface ResponseInterceptor {
  onFulfilled?: (
    response: AjaxResponse,
  ) => AjaxResponse | void | Promise<AjaxResponse | void>
  onRejected?: (error: unknown) => unknown
}

class FetchWrapper {
  defaults: { baseURL: string }
  private _reqInterceptors: RequestInterceptor[] = []
  private _resInterceptors: ResponseInterceptor[] = []

  constructor(config: FetchConfig = {}) {
    this.defaults = {
      baseURL: config.baseURL || baseURL || '',
    }
  }

  get interceptors() {
    return {
      request: {
        use: (onFulfilled: RequestInterceptor['onFulfilled'], onRejected?: RequestInterceptor['onRejected']) => {
          this._reqInterceptors.push({ onFulfilled, onRejected })
        },
      },
      response: {
        use: (onFulfilled: ResponseInterceptor['onFulfilled'], onRejected?: ResponseInterceptor['onRejected']) => {
          this._resInterceptors.push({ onFulfilled, onRejected })
        },
      },
    }
  }

  async _request(
    url: string,
    options: RequestInit = {},
    config: FetchConfig = {},
  ): Promise<AjaxResponse> {
    const requestBaseURL = config.baseURL ?? this.defaults.baseURL
    const fullUrl = url.startsWith('http') ? url : requestBaseURL + url
    let req: AjaxRequest = { url: fullUrl, options }

    for (const interceptor of this._reqInterceptors) {
      if (interceptor.onFulfilled) {
        req = (await interceptor.onFulfilled(req)) || req
      }
    }

    let response: Response
    let data: unknown
    try {
      response = await fetch(req.url, {
        ...req.options,
        headers: {
          'Content-Type': 'application/json',
          ...(req.options?.headers || {}),
        },
      })
      data = await response.json()
    } catch (error) {
      let rejection = error
      for (const interceptor of this._resInterceptors) {
        if (interceptor.onRejected) {
          try {
            return (await interceptor.onRejected(rejection)) as AjaxResponse
          } catch (err) {
            rejection = err
          }
        }
      }
      throw rejection
    }
    let result: AjaxResponse = { data, status: response.status, headers: response.headers, config: req }

    for (const interceptor of this._resInterceptors) {
      if (interceptor.onFulfilled) {
        result = (await interceptor.onFulfilled(result)) || result
      }
    }

    return result
  }

  get<T = unknown>(
    url: string,
    config?: FetchConfig,
  ): Promise<AjaxResponse<T>> {
    return this._request(url, { method: 'GET' }, config) as Promise<
      AjaxResponse<T>
    >
  }

  post<T = unknown>(
    url: string,
    data?: unknown,
    config?: FetchConfig,
  ): Promise<AjaxResponse<T>> {
    return this._request(
      url,
      {
        method: 'POST',
        body: JSON.stringify(data),
      },
      config,
    ) as Promise<AjaxResponse<T>>
  }
}

const ajax = new FetchWrapper({ baseURL })

export default ajax
