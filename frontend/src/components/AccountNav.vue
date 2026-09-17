<template>
  <nav class="account-nav" aria-label="用户中心">
    <RouterLink to="/account/profile">个人资料</RouterLink>
    <RouterLink to="/account/orders">我的订单</RouterLink>
    <RouterLink to="/account/travelers">常用出行人</RouterLink>
    <RouterLink to="/account/favorites">我的收藏</RouterLink>
    <RouterLink to="/account/reviews">我的评价</RouterLink>
    <RouterLink to="/account/messages">消息</RouterLink>
    <RouterLink to="/account/consultations">咨询</RouterLink>
    <RouterLink to="/account/security">账号安全</RouterLink>
  </nav>
</template>

<style scoped>
.account-nav { display: flex; gap: 6px; padding: 12px 20px; overflow-x: auto; border-bottom: 1px solid var(--border-divider); background: white; scrollbar-width: none; }
.account-nav::-webkit-scrollbar { display: none; }
a { flex-shrink: 0; padding: 8px 12px; border-radius: 18px; color: var(--text-secondary); }
a.router-link-active { color: var(--theme-blue); background: var(--theme-blue-tint); }
</style>
