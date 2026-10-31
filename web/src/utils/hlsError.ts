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
  keyLoadError: '密钥拉取失败，请查后端 key 端点与 MinIO 里的 key.bin',
  keyLoadTimeOut: '密钥拉取超时，请查密钥地址的 origin/端口',
  bufferAppendError: '分片写入播放缓冲失败',
};

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
  const hint =
    (typeof status === 'number' ? HINT_BY_STATUS[status] : undefined)
    ?? HINT_BY_DETAILS[details]
    ?? '请查看浏览器控制台与后端日志';

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
  };
}

/**
 * 生成给用户看的一行错误文案。
 *
 * <p>保留原有的"视频源不可达 (HTTP xxx)"骨架（运维/用户已经习惯这个措辞），
 * 但补上**阶段**和**排查方向**，让 "HTTP ??" 这种无信息量的提示不再出现。
 */
export function formatPlaybackError(data: HlsErrorData): string {
  const info = describePlaybackError(data);
  return `视频源不可达（${info.stage} ${info.statusText}）：${info.hint}`;
}
