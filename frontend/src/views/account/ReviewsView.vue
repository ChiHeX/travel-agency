<script setup>
import { onMounted, ref } from 'vue'
import { orderApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const rows = ref([])
const page = ref(1)
const total = ref(0)
const size = 10
const loading = ref(true)
const error = ref('')
let requestId = 0

async function load() {
  const id = ++requestId
  loading.value = true
  error.value = ''
  try {
    // The frozen contract exposes reviews through order details; load only this page.
    const result = await orderApi.list({ status: 'COMPLETED', page: page.value, size })
    const details = await Promise.allSettled(result.items.map((order) => orderApi.detail(order.orderNo)))
    if (id !== requestId) return
    total.value = result.total
    rows.value = result.items.map((order, index) => ({
      order,
      detail: details[index].status === 'fulfilled' ? details[index].value : null,
      error: details[index].status === 'rejected' ? details[index].reason.message : ''
    }))
  } catch (cause) { if (id === requestId) error.value = cause.message || '评价加载失败' }
  finally { if (id === requestId) loading.value = false }
}
function changePage(value) { page.value = value; load() }
onMounted(load)
</script>

<template>
  <div class="container narrow-container page-section">
    <div class="section-head"><div><h2>我的评价</h2><p>按已完成行程分页查看评价，也可以为尚未评价的行程分享体验。</p></div></div>
    <RequestState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无已完成行程，行程结束后可在这里评价。" @retry="load">
      <div class="review-list">
        <article v-for="row in rows" :key="row.order.orderNo" class="admin-panel">
          <h3>{{ row.order.routeName }}</h3><small>{{ row.order.departureStartDate }} · {{ row.order.orderNo }}</small>
          <template v-if="row.detail?.review">
            <el-rate :model-value="row.detail.review.rating" disabled :aria-label="`评分 ${row.detail.review.rating} 星`" />
            <p class="review-content">{{ row.detail.review.content }}</p><small>{{ row.detail.review.createdAt }}</small>
          </template>
          <p v-else-if="row.error" role="alert">{{ row.error }} <button class="text-button" @click="load">重试</button></p>
          <p v-else>尚未评价本次行程</p>
          <div class="review-actions"><RouterLink :to="{ name: 'order-detail', params: { orderNo: row.order.orderNo } }" class="secondary-button">{{ row.detail && !row.detail.review ? '去评价' : '查看订单' }}</RouterLink></div>
        </article>
      </div>
    </RequestState>
    <el-pagination v-if="!loading && !error && total > size" class="account-pagination" layout="prev, pager, next" :pager-count="5" :current-page="page" :page-size="size" :total="total" @current-change="changePage" />
  </div>
</template>

<style scoped>
.review-list { display: grid; gap: 14px; }
h3 { font-size: 16px; } small { color: var(--text-secondary); overflow-wrap: anywhere; }
.review-content { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0 10px; }
.review-actions { margin-top: 14px; }
</style>
