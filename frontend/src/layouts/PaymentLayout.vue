<script setup>
import { useRoute } from 'vue-router'
import PanelIconButton from '@/components/PanelIconButton.vue'

const route = useRoute()
</script>

<template>
  <div class="payment-layout">
    <header class="payment-navigation">
      <PanelIconButton action="back" :fallback-to="{ name: 'order-detail', params: { orderNo: route.params.orderNo } }" />
      <span>旅行订单</span>
    </header>
    <main class="payment-content"><RouterView :key="route.fullPath" /></main>
  </div>
</template>

<style scoped>
.payment-layout { min-height: 100dvh; background: #faf9f7; color: #252522; --border-line: #e5e3df; --bg-canvas: #faf9f7; --bg-subtle: #f5f4f1; --radius-xl: 22px; --shadow-sm: 0 4px 20px rgba(30,30,25,.05); --brand-blue: #0071e3; --brand-blue-subtle: #edf5fc; --danger-red: #ba3a32; }
.payment-navigation { display: flex; align-items: center; gap: 12px; padding: 24px 32px; }
.payment-navigation > span { color: #77766f; font-size: 13px; }
.payment-content { width: min(1120px, calc(100% - 64px)); margin: 32px auto 0; padding-bottom: 72px; }
@media (max-width: 700px) { .payment-navigation { padding: 18px 20px; }.payment-content { width: calc(100% - 40px); margin-top: 20px; padding-bottom: 36px; } }
</style>
