<script setup>
import { nextTick, ref } from 'vue'
import { ElMessageBox } from 'element-plus'

const props = defineProps({
  size: { type: String, default: 'small' },
  pending: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm'])
const confirming = ref(false)

async function confirmCancel() {
  if (confirming.value || props.pending) return
  confirming.value = true
  try {
    await ElMessageBox.confirm('取消后将释放本次占用的团期名额，是否继续？', '取消订单', {
      type: 'warning',
      customClass: 'order-cancel-confirm',
      confirmButtonText: '确认取消',
      cancelButtonText: '保留订单'
    })
    emit('confirm')
    await nextTick()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    confirming.value = false
  }
}
</script>

<template>
  <button type="button" class="cancel-order-button" :class="size" :disabled="pending" @click="confirmCancel">
    {{ pending ? '取消中...' : '取消订单' }}
  </button>
</template>

<style scoped>
.cancel-order-button { display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--status-red); border-radius: var(--radius-full); background: #fff; color: var(--status-red); font-weight: 500; cursor: pointer; transition: background-color .15s ease; }
.cancel-order-button:hover:not(:disabled) { background: var(--status-red-bg); }
.cancel-order-button:disabled { opacity: .5; cursor: not-allowed; }
.cancel-order-button:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 3px; }
.cancel-order-button.small { min-height: 32px; padding: 0 14px; font-size: 12px; }
.cancel-order-button.large { min-height: 44px; padding: 0 24px; font-size: 13px; }
</style>
