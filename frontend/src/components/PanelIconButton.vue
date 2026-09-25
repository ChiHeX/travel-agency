<script setup>
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

const props = defineProps({
  action: { type: String, required: true },
  label: { type: String, default: '' },
  to: { type: [String, Object], default: null },
  active: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['click'])
const icon = computed(() => ({
  back: 'chevron-left',
  share: 'share',
  close: 'close',
  favorite: props.active ? 'heart-filled' : 'heart',
  reset: 'refresh'
})[props.action])
const accessibleLabel = computed(() => props.label || ({
  back: '返回',
  share: '分享',
  close: '关闭',
  favorite: props.active ? '取消收藏' : '收藏',
  reset: '重置'
})[props.action])
</script>

<template>
  <RouterLink v-if="to" class="panel-icon-button" :to="to" :aria-label="accessibleLabel" :title="accessibleLabel">
    <AppIcon :name="icon" size="16" />
  </RouterLink>
  <button v-else type="button" class="panel-icon-button" :class="{ active: action === 'favorite' && active }"
          :aria-label="accessibleLabel" :title="accessibleLabel" :aria-pressed="action === 'favorite' ? active : undefined"
          :disabled="disabled" @click="emit('click', $event)">
    <AppIcon :name="icon" size="16" />
  </button>
</template>

<style scoped>
.panel-icon-button { box-sizing: border-box; display: grid; place-items: center; flex: 0 0 auto; width: 32px; height: 32px; padding: 0; border: 0; border-radius: 50%; color: #49535b; background: #f0f2f4; cursor: pointer; text-decoration: none; transition: background .15s ease, color .15s ease; }
.panel-icon-button:hover { color: #1d1d1f; background: #e3e8ed; }
.panel-icon-button:focus-visible { outline: 2px solid var(--theme-blue); outline-offset: 2px; }
.panel-icon-button.active { color: #d92b45; background: #ffebef; }
.panel-icon-button:disabled { opacity: .55; cursor: wait; }
</style>
