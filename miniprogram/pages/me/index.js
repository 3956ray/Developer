const api = require('../../lib/session');
const PURPOSE = '我们使用微信返回的应用身份建立可撤销的登录会话。登录不代表本店会员资格，也不授予馆方管理权限。本阶段不索取手机号、姓名、头像、位置或人脸。你可以拒绝并继续浏览场馆。这是应用用途说明，不代表已完成微信平台隐私接口授权。';
Page({
  data: { status: '未登录', detail: '登录后可使用个人服务；会员核验尚未开放。', authenticated: false, busy: false,
    configured: api.configured(), testing: api.config.environment === 'test', privacy: PURPOSE, showPrivacy: false },
  onLoad() { this._generation = 0; this._token = api.load(); this._visible = false; },
  onShow() { this._visible = true; if (this._token) { this.setData({ authenticated: false, status: '正在确认登录状态' }); this.refresh('poll'); } this.startTimer(); },
  onHide() { this._visible = false; this._generation++; this._queuedInteraction = null; this.stopTimer(); },
  onUnload() { this._visible = false; this._generation++; this.stopTimer(); },
  startTimer() { this.stopTimer(); this._timer = setInterval(() => { if (this._visible && this._token) this.refresh('poll'); }, 60000); },
  stopTimer() { if (this._timer) clearInterval(this._timer); if (this._expiryTimer) clearTimeout(this._expiryTimer); },
  showPrivacy() { this.setData({ showPrivacy: !this.data.showPrivacy }); },
  async acceptSession(response) {
    const s = response.data;
    if (!s || s.environment !== api.config.environment || (s.simulation && api.config.environment !== 'test') ||
        !Number.isFinite(Date.parse(s.expiresAt)) || !Number.isFinite(Date.parse(s.idleExpiresAt))) throw { code: 'INVALID_RESPONSE' };
    const remaining = Math.min(Date.parse(s.expiresAt), Date.parse(s.idleExpiresAt)) - Date.parse(response.serverNow);
    if (remaining <= 0) throw { status: 401, code: 'SESSION_INVALID' };
    this.setData({ authenticated: true, status: s.simulation ? '模拟身份 · 已登录' : '已登录', detail: '仅建立本应用身份；会员资格和馆方权限需另外核验。' });
    clearTimeout(this._expiryTimer);
    // Delay is derived only from server timestamps. After restart, state is never trusted before a new response.
    this._expiryTimer = setTimeout(() => { this.setData({ authenticated: false, status: '登录状态待确认', detail: '会话可能已到期，请主动刷新。' }); }, Math.min(remaining, 2147483647));
  },
  forget() { this._pendingLogout = null; this._token = null; this._generation++; try { api.clear(); } catch (_) {} this.setData({ authenticated: false }); },
  showError(error) {
    if (error.status === 401) { this.forget(); this.setData({ status: '登录已失效', detail: '请主动重新登录，场馆浏览仍可使用。' }); }
    else this.setData({ authenticated: false, status: '暂时无法确认', detail: error.code === 'NOT_CONFIGURED' ? '服务尚未配置。' : '请检查连接后重试；不会自动重新登录。' });
  },
  async refresh(interaction) {
    if (!this._token || this.data.busy) return;
    if (this._refreshing) { this._queuedInteraction = interaction === 'interactive' ? 'interactive' : (this._queuedInteraction || 'poll'); return; }
    const generation = this._generation; this._refreshing = true;
    try { const response = await api.request('/session?interaction=' + interaction, 'GET', undefined, this._token); if (generation === this._generation) await this.acceptSession(response); }
    catch (e) { if (generation === this._generation) this.showError(e); }
    finally {
      this._refreshing = false;
      const queued = this._queuedInteraction; this._queuedInteraction = null;
      if (queued && this._visible && this._token) this.refresh(queued);
    }
  },
  refreshNow() { return this.refresh('interactive'); },
  login() {
    if (this.data.busy || !api.configured()) return;
    wx.showModal({ title: '登录用途说明', content: PURPOSE, confirmText: '同意登录', cancelText: '暂不登录',
      success: async result => {
        if (!result.confirm) { this.setData({ detail: '已拒绝本次登录，你仍可浏览场馆。' }); return; }
        if (this.data.busy) return;
        this.setData({ busy: true }); const generation = ++this._generation;
        try {
          const code = await api.loginCode();
          const response = await api.request('/sessions/exchange', 'POST', { code, privacyNoticeVersion: 'cp1-purpose-v1', consent: true });
          if (generation !== this._generation) return;
          const token = response.data.token;
          if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(token)) throw { code: 'INVALID_RESPONSE' };
          await this.acceptSession(response); api.save(token); this._token = token; this._pendingLogout = null;
        } catch (e) { if (generation === this._generation) this.showError(e); }
        finally { this.setData({ busy: false }); }
      }
    });
  },
  async openMaintenance() {
    if (!this._token || this.data.busy) return;
    try {
      const response = await api.request('/operator/role', 'GET', undefined, this._token);
      if (response.data.isOperator) wx.navigateTo({ url: '/pages/maintenance/index' });
      else this.setData({ detail: '当前账号没有馆方维护权限。' });
    } catch (e) { this.showError(e); }
  },
  async logout() {
    if (!this._token || this.data.busy || this._refreshing) return;
    this.setData({ busy: true }); const generation = ++this._generation;
    try {
      const current = await api.request('/session?interaction=interactive', 'GET', undefined, this._token);
      if (!this._pendingLogout || this._pendingLogout.token !== this._token) {
        const operationId = await api.operationId();
        this._pendingLogout = { token: this._token, body: { operationId, requestCreatedAt: current.serverNow, expectedRevision: current.data.sessionRevision } };
      }
      const body = this._pendingLogout.body, operationId = body.operationId;
      try { await api.request('/session/logout', 'POST', body, this._token); }
      catch (error) {
        // Never create a new key on response loss. A revoked session is itself sufficient evidence of lost access.
        try { await api.request('/operations/' + operationId + '?type=session.logout', 'GET', undefined, this._token); }
        catch (lookup) { if (lookup.status === 401) error = lookup; }
        if (error.status !== 401) throw error;
      }
      if (generation === this._generation) { this.forget(); this.setData({ status: '已退出登录', detail: '当前会话已失效，仍可浏览场馆。' }); }
    } catch (e) { if ([400, 409, 422].includes(e.status)) this._pendingLogout = null; if (generation === this._generation) this.showError(e); }
    finally { this.setData({ busy: false }); }
  }
});
