const api = require('./session');
const presentation = require('./observation-view');
const scope = () => api.scope();
const CACHE = 'gym.observation-cache.v1', PENDING = 'gym.observation-write.v1';
function monotonic() {
  try { const value = wx.getPerformance().now(); return Number.isFinite(value) ? value : null; } catch (_) { return null; }
}
module.exports = function makePage(operator) {
  return {
    data: { title: '尚未更新', detail: '正在获取现场信息。', history: '', observedText: '', validity: '', live: false,
      gymName: '场馆', venueNote: '场馆营业与联系资料待确认。', busy: false, refreshing: false, authorized: false,
      simulation: api.config.environment === 'test', message: '', pending: false, canReplace: false },
    onLoad() {
      this._generation = 0; this._visible = false; this._clock = presentation.clock(monotonic);
      try { const cached = wx.getStorageSync(CACHE); if (cached && cached.scope === scope()) this._snapshot = cached.snapshot; } catch (_) {}
      if (operator) { try { const p = wx.getStorageSync(PENDING); if (p && p.scope === scope()) this._pending = p; } catch (_) {} }
      this.setData({ pending: Boolean(this._pending) }); this.render(false);
    },
    onShow() { this._visible = true; this._clock.reset(); this.setData({ authorized: false }); this.render(false); this.refresh();
      this._poll = setInterval(() => { if (this._visible) this.refresh(); }, 60000);
      this._tick = setInterval(() => { if (this._visible) this.render(this._connected); }, 1000); },
    onHide() { this._visible = false; this._generation++; this._queued = false; this._clock.reset(); clearInterval(this._poll); clearInterval(this._tick); },
    onUnload() { this.onHide(); },
    render(connected) {
      const s = this._snapshot;
      this.setData(presentation.view(s && s.observation, this._clock.now(), Boolean(connected), s && s.gym && s.gym.timeZone));
      if (s && s.gym) this.setData({ gymName: s.gym.name || '场馆' });
    },
    async refresh() {
      if (!this._visible) return;
      if (this._reading || this.data.busy) { this._queued = true; return; }
      this._reading = true; this.setData({ refreshing: true }); const generation = this._generation;
      try {
        if (operator) {
          const token = api.load(); if (!token) throw { status: 401 };
          const role = await api.request('/operator/role', 'GET', undefined, token);
          if (!role.data.isOperator) throw { status: 403 };
          if (generation !== this._generation || !this._visible) return;
        }
        const started = monotonic(); const response = await api.request('/venue', 'GET');
        if (generation !== this._generation || !this._visible) return;
        const observation = response.data && response.data.observation;
        if (!observation || observation.environment !== api.config.environment || (api.config.environment === 'store' && observation.simulation)) throw { code: 'INVALID_RESPONSE' };
        this._clock.accept(response.serverNow, started); this._snapshot = response.data; this._connected = true;
        this._revision = observation.revision;
        try { wx.setStorageSync(CACHE, { scope: scope(), snapshot: response.data }); } catch (_) {}
        this.setData({ authorized: operator, message: this.data.message }); this.render(true);
      } catch (e) {
        if (generation === this._generation && this._visible) {
          this._connected = false; this._clock.reset(); this.render(false);
          if (operator && [401, 403].includes(e.status)) this.setData({ authorized: false, message: e.status === 401 ? '请先在“我的”主动登录。' : '当前账号没有馆方维护权限。' });
        }
      } finally {
        this._reading = false; this.setData({ refreshing: false });
        const queued = this._queued; this._queued = false; if (queued && this._visible) this.refresh();
      }
    },
    refreshNow() { return this.refresh(); },
    choose(event) {
      if (!operator || !this.data.authorized || this.data.busy || this._reading || this._pending) return;
      const state = event.currentTarget.dataset.state;
      if (!['quiet', 'moderate', 'busy', 'unknown', 'paused', 'withdrawn'].includes(state)) return;
      const publish = ['quiet', 'moderate', 'busy'].includes(state), expectedRevision = this._revision;
      wx.showModal({ title: publish ? '确认刚刚现场观察' : '确认修改观察状态',
        content: publish ? '请确认你刚刚查看了现场。超过一分钟未确认提交结果时，需要重新观察。' : '该操作会替换当前状态，不会恢复旧观察。', confirmText: '确认提交',
        success: result => { if (result.confirm) this.submitNew(state, publish, expectedRevision); } });
    },
    async submitNew(state, publish, confirmedRevision) {
      if (!this._visible || !this.data.authorized || this.data.busy || this._reading || this._pending) return;
      const time = this._clock.now();
      if (time === null) { this.setData({ message: '时间尚未确认，请先刷新。' }); return; }
      this.setData({ busy: true }); const generation = this._generation, token = api.load();
      try {
        const requestCreatedAt = new Date(time).toISOString(), expectedRevision = confirmedRevision === undefined ? this._revision : confirmedRevision;
        const session = await api.request('/session?interaction=poll', 'GET', undefined, token);
        const operationId = await api.operationId();
        if (generation !== this._generation || !this._visible) return;
        const body = { operationId, requestCreatedAt, expectedRevision, ...(publish ? { level: state, observedJustNow: true } : { state, reasonCategory: state === 'unknown' ? 'cannot-assess' : state === 'paused' ? 'updates-paused' : 'correction' }) };
        const pending = { scope: scope(), userId: session.data.userId, type: publish ? 'observation.publish' : 'observation.control', body };
        wx.setStorageSync(PENDING, pending); this._pending = pending; // Persist intent before any write request.
        this.setData({ pending: true, canReplace: false }); await this.sendPending(token, generation);
      } catch (e) { if (generation === this._generation) this.writeError(e); }
      finally { this.setData({ busy: false }); if (this._visible) this.refresh(); }
    },
    clearPending() { this._pending = null; try { wx.removeStorageSync(PENDING); } catch (_) {} this.setData({ pending: false, canReplace: false }); },
    async sendPending(token, generation) {
      const pending = this._pending;
      await api.request(pending.type === 'observation.publish' ? '/operator/observations' : '/operator/observations/control', 'POST', pending.body, token);
      if (generation === this._generation) { this.clearPending(); this.setData({ message: '操作已提交，请以刷新后的当前状态为准。' }); }
    },
    writeError(error) {
      if ([400, 409, 422].includes(error.status)) {
        this.clearPending(); this.setData({ message: error.status === 409 ? '状态已被其他人更新，请刷新并重新确认。' : '请求无效或已超时，请重新现场观察或确认。' });
      } else if ([401, 403].includes(error.status)) {
        this.setData({ authorized: false, message: error.status === 401 ? '登录已失效，请主动登录后查询原操作。' : '馆方权限已撤销，不能继续操作。' });
      } else this.setData({ message: this._pending ? '结果待确认，请查询原操作；不会自动建立新请求。' : '无法建立安全请求，请刷新后重试。' });
    },
    async queryPending() {
      if (!this._pending || this.data.busy || !this._visible) return;
      this.setData({ busy: true }); const generation = this._generation;
      try {
        const token = api.load(), session = await api.request('/session?interaction=poll', 'GET', undefined, token);
        if (session.data.userId !== this._pending.userId) { this.setData({ message: '请使用原操作账号查询，不能换账号重发。' }); return; }
        const p = this._pending; const result = await api.request('/operations/' + p.body.operationId + '?type=' + p.type, 'GET', undefined, token);
        if (generation !== this._generation) return;
        if (result.data.state === 'committed') { this.clearPending(); this.setData({ message: '原操作已提交，正在读取当前状态。' }); }
        else this.setData({ canReplace: true, message: '未找到可确认的结果，不代表已回滚。可重试原请求；超过窗口请重新观察并确认。' });
      } catch (e) { if (generation === this._generation) this.writeError(e); }
      finally { this.setData({ busy: false }); if (this._visible) this.refresh(); }
    },
    async retryPending() {
      if (!this._pending || this.data.busy || !this.data.authorized || !this.data.canReplace) return;
      const time = this._clock.now(), maximumAge = this._pending.type === 'observation.publish' ? 60000 : 300000;
      if (time === null || time - Date.parse(this._pending.body.requestCreatedAt) > maximumAge) { this.setData({ message: '原请求已超时，必须重新观察或确认，不能补发旧等级。' }); return; }
      this.setData({ busy: true }); const generation = this._generation;
      try {
        const token = api.load(), session = await api.request('/session?interaction=poll', 'GET', undefined, token);
        if (session.data.userId !== this._pending.userId) throw { status: 403 };
        if (generation === this._generation) await this.sendPending(token, generation);
      } catch (e) { if (generation === this._generation) this.writeError(e); }
      finally { this.setData({ busy: false }); if (this._visible) this.refresh(); }
    },
    replacePending() {
      if (!this._pending || !this.data.canReplace || this.data.busy) return;
      wx.showModal({ title: '重新观察并确认', content: '旧操作结果仍需以当前记录为准。请重新查看现场、刷新版本，再建立新操作。',
        success: result => { if (result.confirm) { this.clearPending(); this.setData({ message: '请重新现场观察后选择等级。' }); this.refresh(); } } });
    }
  };
};
