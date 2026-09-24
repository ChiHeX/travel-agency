<template>
  <div class="account-navigation">
    <div class="account-navigation-title">设置</div>
    <nav class="account-nav" aria-label="用户中心">
      <div class="account-nav-inner">
        <RouterLink to="/account/profile">个人资料</RouterLink>
        <RouterLink to="/account/orders">我的订单</RouterLink>
        <RouterLink to="/account/travelers">常用出行人</RouterLink>
        <RouterLink to="/account/favorites">我的收藏</RouterLink>
        <RouterLink to="/account/reviews">我的评价</RouterLink>
        <RouterLink to="/account/messages">消息</RouterLink>
        <RouterLink to="/account/consultations">咨询</RouterLink>
        <RouterLink to="/account/security">账号安全</RouterLink>
      </div>
    </nav>
  </div>
</template>

<style scoped>
.account-navigation {
  flex: 0 0 auto;
  padding-top: 16px;
  border-bottom: 1px solid #dedee3;
  background: #fff;
}

.account-navigation-title {
  margin-left: 24px;
  font-size: 21px;
  font-weight: 700;
  line-height: 28px;
}

.account-nav {
  overflow-x: auto;
  scrollbar-width: none;
}

.account-nav::-webkit-scrollbar { display: none; }

.account-nav-inner {
  display: flex;
  align-items: stretch;
  gap: 22px;
  width: min(900px, calc(100% - 48px));
  min-width: max-content;
  height: 42px;
  margin-left: 24px;
}

a {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  padding: 1px 0 0;
  border-bottom: 2px solid transparent;
  color: #303036;
  font-size: 13px;
  font-weight: 650;
  text-decoration: none;
}

a:hover { color: #18181b; }
a.router-link-active { border-bottom-color: var(--theme-blue); color: #18181b; }

@media (max-width: 768px) {
  .account-navigation-title { margin-left: 16px; }
  .account-nav-inner {
    gap: 18px;
    width: max-content;
    min-width: 0;
    height: 42px;
    margin: 0;
    padding: 0 16px;
  }
}
</style>
