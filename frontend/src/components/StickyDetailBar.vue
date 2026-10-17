<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import PanelIconButton from '@/components/PanelIconButton.vue'

defineProps({
  title: { type: String, required: true },
  fallbackTo: { type: [String, Object], required: true },
  overlay: { type: Boolean, default: false }
})

defineEmits(['share'])

const bar = ref(null)
const scrolled = ref(false)
let scrollContainer

function updateScrollState() {
  scrolled.value = (scrollContainer?.scrollTop || 0) > 64
}

onMounted(() => {
  scrollContainer = bar.value?.parentElement
  scrollContainer?.addEventListener('scroll', updateScrollState, { passive: true })
  updateScrollState()
})

onBeforeUnmount(() => scrollContainer?.removeEventListener('scroll', updateScrollState))
</script>

<template>
  <div ref="bar" class="sticky-detail-bar" :class="{ overlay, scrolled }">
    <PanelIconButton action="back" :fallback-to="fallbackTo" />
    <strong class="sticky-title" :class="{ visible: scrolled }" :title="title">{{ title }}</strong>
    <PanelIconButton action="share" @click="$emit('share')" />
  </div>
</template>

<style scoped>
.sticky-detail-bar { position: sticky; top: 0; z-index: 20; box-sizing: border-box; display: grid; grid-template-columns: 32px minmax(0, 1fr) 32px; align-items: center; gap: 12px; height: 56px; padding: 0 20px; background: rgba(247,250,249,.92); backdrop-filter: blur(18px); }
.sticky-detail-bar.overlay { margin-bottom: -56px; }
.sticky-detail-bar.overlay:not(.scrolled) { background: transparent; backdrop-filter: none; }
.sticky-title { overflow: hidden; color: #1d1d1f; font-size: 15px; font-weight: 700; text-align: center; text-overflow: ellipsis; white-space: nowrap; opacity: 0; }
.sticky-title.visible { opacity: 1; }
</style>
