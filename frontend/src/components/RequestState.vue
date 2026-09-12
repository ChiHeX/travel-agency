<script setup>
defineProps({ loading: Boolean, error: { type: String, default: '' }, empty: Boolean, emptyText: { type: String, default: '暂无相关数据' } })
defineEmits(['retry'])
</script>

<template>
  <div v-if="loading" class="request-state" role="status"><el-skeleton :rows="5" animated /></div>
  <div v-else-if="error" class="request-state" role="alert">
    <strong>暂时无法加载</strong><p>{{ error }}</p>
    <button type="button" class="secondary-button" @click="$emit('retry')">重新加载</button>
  </div>
  <div v-else-if="empty" class="request-state"><p>{{ emptyText }}</p></div>
  <slot v-else />
</template>

<style scoped>
.request-state { padding: 28px 18px; border: 1px solid var(--border-divider); border-radius: 12px; background: white; text-align: center; }
.request-state p { margin: 8px 0 14px; color: var(--text-secondary); overflow-wrap: anywhere; }
</style>
