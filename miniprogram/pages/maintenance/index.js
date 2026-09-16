const page = require('../../lib/observation-page')(true);
page.openMembers = function () { if (this.data.authorized) wx.navigateTo({ url: '/pages/member-operator/index' }); };
Page(page);
