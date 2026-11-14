/**
 * 把 hls.js 的 ERROR 事件翻译成"能据此行动"的中文提示。
 *
 * <p><b>为什么需要它</b>：VideoDetail.vue 原来只取 {@code data.response?.code}，
 * 而 hls.js 在**超时**路径上根本不挂 {@code response}
 * （见 hls.js {@code handleNetworkError}：{@code if (response) {...}}），
 * 于是 {@code status} 是 {@code undefined} → 一律显示 "HTTP ??"。
 *
 * <p>"HTTP ??" 对运维**零信息量** —— 它同时对应：
 * <ul>
 *   <li>分片地址的 origin/端口猜错（连不上，只能等超时）</li>
 *   <li>后端真的慢（MinIO 读慢）</li>
 *   <li>浏览器/防火墙丢包</li>
 * </ul>
 * 而 hls.js 自己在 {@code data.details} 里给了**精确到阶段**的结论
 * （{@code manifestLoadError} / {@code fragLoadError} / {@code keyLoadError} /
 * {@code manifestLoadTimeOut} …），原来被丢进了 console 就完事。
 *
 * <p>本模块把 {@code details} 映射成中文，并给出对应的排查方向。
 */

/** hls.js 的 ErrorDetails（只列播放链路会用到的） */
export type HlsErrorDetails =
  | 'manifestLoadError'
  | 'manifestLoadTimeOut'
  | 'manifestParsingError'
  | 'levelLoadError'
  | 'levelLoadTimeOut'
  | 'fragLoadError'
  | 'fragLoadTimeOut'
  | 'fragDecryptError'
  | 'keyLoadError'
  | 'keyLoadTimeOut'
  | 'bufferAppendError'
  | string;

/** hls.js ERROR 事件的 data（只取用得到的字段） */
export interface HlsErrorData {
  type?: string;
  details?: HlsErrorDetails;
  fatal?: boolean;
  /** 只有真正收到 HTTP 响应时才有；超时路径上 hls.js 不挂这个字段 */
  response?: { code?: number; text?: string; url?: string };
  /** 出错的资源 URL（manifest / segment / key） */
  url?: string;
  /** 底层错误对象 */
  error?: { message?: string };
}

/** 阶段 → 中文名（决定"该去看哪"） */
const STAGE_BY_DETAILS: Record<string, string> = {
  manifestLoadError: '播放列表',
  manifestLoadTimeOut: '播放列表',
  manifestParsingError: '播放列表',
  levelLoadError: '子播放列表',
  levelLoadTimeOut: '子播放列表',
  fragLoadError: '视频分片',
  fragLoadTimeOut: '视频分片',
  fragDecryptError: '视频分片',
  keyLoadError: '解密密钥',
  keyLoadTimeOut: '解密密钥',
  bufferAppendError: '视频缓冲',
};

/** 阶段 → 排查方向 */
const HINT_BY_DETAILS: Record<string, string> = {
  manifestLoadError: '后端拒绝或找不到播放列表，请查后端日志里 m3u8_served / m3u8_serve_failed',
  manifestLoadTimeOut: '后端迟迟没响应，请查 MinIO 连通性与后端线程池',
  manifestParsingError: '返回的播放列表格式不对，请查后端 m3u8 改写逻辑',
  levelLoadError: '子播放列表拉取失败，请查后端日志',
  levelLoadTimeOut: '子播放列表拉取超时',
  fragLoadError: '分片拉取失败，请查分片地址的 origin/端口是否与播放列表一致',
  fragLoadTimeOut: '分片拉取超时，请查分片地址的 origin/端口是否与播放列表一致',
  fragDecryptError: '分片解密失败，密钥与分片不匹配（转码后密钥换过？）',
  keyLoadError: '密钥拉取失败，请查后端 key 端点、MinIO 里的 key.bin，以及播放列表里 EXT-X-KEY 的地址是否为空',
  keyLoadTimeOut: '密钥拉取超时，请查密钥地址的 origin/端口',
  bufferAppendError: '分片写入播放缓冲失败',
};

/**
 * hls.js 内部的"密钥账本对不上"报错。
 *
 * <p>原文 {@code after key load, decryptdata unset or changed} 出自
 * hls.js {@code key-loader.ts} 的 onSuccess：密钥的 HTTP 请求**其实成功了**，
 * 但它发现自己的 keyLoaderInfo 缓存里那条记录已经不是本次加载的那条
 * （被中止/重建的旧请求留下的残骸），于是判定"状态变了"并报错。
 *
 * <p>所以它**不是**网络问题，也不是密钥地址问题 —— 换一个提示，
 * 否则运维会去查 key 端点、查 MinIO，方向完全不对。
 */
const KEY_STATE_DESYNC = 'after key load, decryptdata unset or changed';

const KEY_STATE_DESYNC_HINT =
  '播放器的密钥状态失步（密钥请求其实成功了，是 hls.js 内部缓存对不上）。'
  + '已自动重建播放器重试；若反复出现，请把浏览器控制台的 HLS 日志一并发给运维';

/** 状态码 → 面向用户的处置建议（比"查日志"更直接） */
const HINT_BY_STATUS: Record<number, string> = {
  404: '视频尚未转码完成，或播放产物在对象存储中不存在。请稍后重试或联系管理员处理',
  401: '播放令牌无效或已过期，请刷新页面重新获取播放地址',
  403: '没有播放该视频的权限，请联系管理员授权',
  503: '对象存储暂时不可用，请稍后重试或联系管理员',
};

export interface PlaybackErrorInfo {
  /** HTTP 状态码；没有响应时为 undefined */
  status?: number;
  /** 给人看的状态描述，例如 "HTTP 404" 或 "无响应（超时）" */
  statusText: string;
  /** 失败发生在哪个阶段，例如 "视频分片" */
  stage: string;
  /** 排查方向 */
  hint: string;
  /** 出错资源的 URL（token 已打码） */
  url?: string;
  /**
   * hls.js 自己给的失败原因原文，例如
   * `Invalid key URI: ""`、`HTTP Error 404 loading key `。
   *
   * <p>这一条**不能省**：hls.js 有好几条 key 相关的失败路径，
   * 其中"URI 为空"那条**压根不会发 HTTP 请求**，
   * 于是 {@code data.response} 是 undefined —— 前端只能显示
   * "无响应（超时或连接被拒）"，把"地址是空的"说成了"网络不通"。
   * 带上原文，运维一眼就能看出是地址问题还是网络问题。
   */
  reason?: string;
}

/**
 * 从 URL 里抹掉 token 参数。
 *
 * <p>播放地址里带的是**可用的播放 JWT**，把它原样打到日志/告警里
 * 等于把凭据泄出去（项目安全约定：日志中不得打印 token 本身）。
 */
export function redactTokenFromUrl(url?: string): string | undefined {
  if (!url) return undefined;
  return url.replace(/([?&]token=)[^&]*/gi, '$1***');
}

/**
 * 从**任意文本**里抹掉 token 参数。
 *
 * <p>为什么 URL 版本不够（2026-11-13 修，第三轮审计 §6）：
 *
 * <p>hls.js 的错误消息里**嵌着完整的密钥 URL**。看它的 key-loader：
 * <pre>{@code
 * return Promise.reject(createKeyLoadError(
 *   frag, ErrorDetails.KEY_LOAD_ERROR,
 *   new Error("Invalid key URI: \"" + uri + "\"")));
 * }</pre>
 *
 * <p>那个 {@code uri} 就是后端改写过的密钥地址
 * （{@code /api/v1/playback/{id}/key?token=<播放 JWT>}）⇒
 * {@code data.error.message} 里**带着可用的播放凭据**。
 *
 * <p>而这个 message 会经 {@code data.error?.message} 流进
 * ①控制台日志 ②用户看到的错误文案 ⇒ 两处都会泄。
 *
 * <p>所以除了 URL 本身，**任何文本**都要过一遍打码。
 */
export function redactTokenFromText(text?: string): string | undefined {
  if (!text) return undefined;
  return text.replace(/([?&]token=)[^"'\s&]*/gi, '$1***');
}

/**
 * 把 hls.js 的 ERROR 事件翻译成可排查的中文信息。
 *
 * <p>注意：{@code data.response} 缺失**不代表**没有失败 ——
 * hls.js 的超时路径压根不挂 response。所以这里把"没有响应"
 * 单独表述成"无响应（超时）"，而不是伪装成一个状态码。
 */
export function describePlaybackError(data: HlsErrorData): PlaybackErrorInfo {
  const details = data.details ?? '';
  const status = data.response?.code;
  const stage = STAGE_BY_DETAILS[details] ?? '播放资源';

  // 状态码的处置建议优先（404 = 还没转码完，是最常见的那个）；
  // 没有状态码时退回按阶段的排查方向。
  let hint =
    (typeof status === 'number' ? HINT_BY_STATUS[status] : undefined)
    ?? HINT_BY_DETAILS[details]
    ?? '请查看浏览器控制台与后端日志';

  // hls.js 内部状态失步的特例：密钥请求**成功了**，问题不在后端也不在网络。
  // 这条必须单独说，否则运维会被引去查 key 端点/对象存储（方向全错）。
  //
  // ⚠️ reason 要**打码** —— hls.js 的 key-loader 把完整的密钥 URL
  // （带 ?token=<播放 JWT>）嵌在错误消息里（"Invalid key URI: ..."）。
  const reason = redactTokenFromText(data.error?.message);
  if (reason && reason.includes(KEY_STATE_DESYNC)) {
    hint = KEY_STATE_DESYNC_HINT;
  }

  // 有响应 → 用真实状态码；没有响应 → hls.js 只在超时路径上这样，
  // 必须说清楚是"没等到响应"，而不是显示一个 ?? 让人以为是状态码缺失。
  const statusText =
    typeof status === 'number' ? `HTTP ${status}` : '无响应（超时或连接被拒）';

  return {
    status,
    statusText,
    stage,
    hint,
    url: redactTokenFromUrl(data.url ?? data.response?.url),
    reason,
  };
}

/**
 * 生成给用户看的一行错误文案。
 *
 * <p>保留原有的"视频源不可达 (HTTP xxx)"骨架（运维/用户已经习惯这个措辞），
 * 但补上**阶段**和**排查方向**，让 "HTTP ??" 这种无信息量的提示不再出现。
 *
 * <p>末尾再附上 hls.js 的失败原因原文 —— 没有它，
 * "没发出去的请求"和"网络不通"会长得一模一样。
 */
export function formatPlaybackError(data: HlsErrorData): string {
  const info = describePlaybackError(data);
  const reason = info.reason ? `（hls.js：${info.reason}）` : '';
  return `视频源不可达（${info.stage} ${info.statusText}）：${info.hint}${reason}`;
}
