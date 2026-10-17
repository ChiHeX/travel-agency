<script setup>
import { useRoute } from 'vue-router'
import PanelIconButton from '@/components/PanelIconButton.vue'

const route = useRoute()
</script>

<template>
  <div class="payment-layout">
    <header class="payment-navigation">
      <PanelIconButton class="checkout-back" action="back" :fallback-to="route.name === 'order-detail' ? { name: 'account-orders' } : { name: 'order-detail', params: { orderNo: route.params.orderNo } }" />
    </header>
    <main class="payment-content"><RouterView :key="route.fullPath" /></main>
  </div>
</template>

<style scoped>
.payment-layout { min-height: 100dvh; background: var(--app-bg); color: var(--text-primary); }
.payment-navigation { display: flex; align-items: center; padding: 24px 16px; }
.checkout-back { background: transparent; }
.payment-content { width: min(1200px, calc(100% - 80px)); margin: 24px auto 0; padding-bottom: 80px; }
@media (max-width: 700px) { .payment-navigation { padding: 18px 20px; }.payment-content { width: calc(100% - 40px); margin-top: 20px; padding-bottom: 36px; } }
</style>
