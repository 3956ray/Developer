const LABELS = { never: '尚未更新', quiet: '较空', moderate: '适中', busy: '较忙', unknown: '暂无法判断', paused: '暂停更新', withdrawn: '记录已撤回', expired: '忙闲信息已过期', unavailable: '暂无法提供忙闲信息' };
const LEVELS = ['quiet', 'moderate', 'busy'];
function clock(read) {
  let anchor = null, last = null;
  return {
    reset() { anchor = null; last = null; },
    accept(serverNow, started) {
      const ended = read(), server = Date.parse(serverNow);
      if (!Number.isFinite(server) || !Number.isFinite(started) || !Number.isFinite(ended) || ended < started) { anchor = null; return false; }
      // Add the whole round trip conservatively; transport latency must never grant extra freshness.
      anchor = { server: server + ended - started, mono: ended }; last = ended; return true;
    },
    now() {
      const value = read();
      if (!anchor || !Number.isFinite(value) || value < last || value < anchor.mono) { anchor = null; return null; }
      last = value; return anchor.server + value - anchor.mono;
    }
  };
}
function formatTime(value, timeZone) {
  const time = Date.parse(value);
  if (!Number.isFinite(time)) return '';
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: timeZone || 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(time)); }
  catch (_) { return new Date(time).toISOString() + ' (UTC)'; }
}
function view(observation, now, connected, timeZone) {
  const o = observation;
  const basic = { title: '尚未更新', live: false, detail: '等待馆方现场观察。', history: '', observedText: '', validity: '', simulation: Boolean(o && o.simulation) };
  if (!o) return connected ? basic : { ...basic, title: '暂无法提供忙闲信息', detail: '连接失败或服务未配置，请重试。' };
  const prior = o.level || o.historicalLevel;
  const date = formatTime(o.observedAt || o.publishedAt, timeZone);
  if (!connected || now === null) return { ...basic, title: '暂无法提供忙闲信息', detail: connected ? '无法确认时间，请刷新。' : '未能取得最新信息，请重试。', history: date ? '上次记录：' + (LABELS[prior || o.state] || '不可用') + ' · ' + date : '', observedText: date };
  if (!Object.prototype.hasOwnProperty.call(LABELS, o.state)) return { ...basic, title: LABELS.unavailable, detail: '记录异常，请刷新。' };
  const observed = Date.parse(o.observedAt), end = Date.parse(o.validUntil);
  if (LEVELS.includes(o.state) || o.state === 'expired') {
    if (!Number.isFinite(observed) || !Number.isFinite(end) || observed > now || end - observed !== 900000) return { ...basic, title: LABELS.unavailable, detail: '记录时间异常，请刷新。' };
    if (now >= end || o.state === 'expired') return { ...basic, title: LABELS.expired, observedText: date, history: '历史观察：' + (LABELS[prior] || '不可用'), detail: '请等待馆方重新观察，旧等级不代表当前情况。' };
    return { ...basic, title: LABELS[o.state], live: true, detail: '馆方现场观察 · 到店时情况可能已变化', observedText: date,
      validity: '观察于约 ' + Math.floor((now - observed) / 1000) + ' 秒前 · 剩余有效 ' + Math.max(0, Math.ceil((end - now) / 1000)) + ' 秒' };
  }
  return { ...basic, title: LABELS[o.state], observedText: date, detail: o.state === 'never' ? '等待馆方现场观察。' : '当前没有可用的现场忙闲等级。' };
}
module.exports = { clock, view, LABELS };
