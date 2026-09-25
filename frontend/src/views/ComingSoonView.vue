<script setup>
import { useRouter } from 'vue-router'
import { returnToPrevious } from '@/utils/navigation'

defineProps({ title: { type: String, default: '功能页面' } })
const router = useRouter()
</script>

<template><div class="empty-page"><div class="empty-page-mark">✦</div><span class="eyebrow">FRAMEWORK READY</span><h2>{{ title }}</h2><p>页面路由、权限入口与后端模块已预留，核心业务闭环稳定后可继续接入。</p><button type="button" class="primary-button" @click="returnToPrevious(router, { name: 'admin-dashboard' })">返回上一页</button></div></template>

<style scoped>.empty-page{min-height:420px;display:grid;place-content:center;justify-items:center;text-align:center}.empty-page-mark{width:55px;height:55px;border-radius:18px;display:grid;place-items:center;background:var(--mint);color:var(--teal);font-size:25px;margin-bottom:18px}.empty-page h2{font:500 30px Georgia,serif;margin:12px 0}.empty-page p{max-width:360px;color:var(--muted);font-size:13px;line-height:1.8;margin:0 0 22px}
</style>
