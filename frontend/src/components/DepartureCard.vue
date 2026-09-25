<script setup>
import { computed } from 'vue'

const props = defineProps({
  departure: { type: Object, required: true },
  selected: { type: Boolean, default: false },
  interactive: { type: Boolean, default: false }
})
const emit = defineEmits(['select'])
const seats = computed(() => props.departure.availableSeats == null
  ? null : Number(props.departure.availableSeats))
const bookable = computed(() => (!props.departure.status || props.departure.status === 'OPEN')
  && seats.value != null && seats.value > 0)

function select() {
  if (props.interactive && bookable.value) emit('select')
}
</script>

<template>
  <div class="departure-card-item" :class="{ selected, soldout: seats === 0, interactive }"
       :role="interactive ? 'button' : undefined"
       :tabindex="interactive && bookable ? 0 : undefined"
       :aria-disabled="interactive ? String(!bookable) : undefined"
       @click="select" @keydown.enter.prevent="select" @keydown.space.prevent="select">
    <div class="dep-dates">
      <strong>{{ departure.startDate }}</strong>
      <span>至 {{ departure.endDate }}</span>
    </div>
    <div class="dep-prices">
      <span class="adult">¥{{ departure.adultPrice }}<small>/成人</small></span>
      <span class="child">¥{{ departure.childPrice }}<small>/儿童</small></span>
    </div>
    <div class="dep-seats">
      <span v-if="seats != null && seats > 0" class="tag success">余 {{ seats }}</span>
      <span v-else-if="seats === 0" class="tag danger">已满</span>
      <span v-else class="tag">余量待同步</span>
    </div>
  </div>
</template>

<style scoped>
.departure-card-item {
  display: grid;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  transition: all 0.15s ease;
}
.departure-card-item.interactive { cursor: pointer; }
.departure-card-item[aria-disabled='true'] { cursor: not-allowed; opacity: 0.62; }
.departure-card-item.interactive:hover { background: rgba(255, 255, 255, 0.95); }
.departure-card-item.selected { border-color: var(--theme-blue); background: var(--theme-blue-tint); }
.dep-dates strong { display: block; font-size: 13px; color: var(--text-primary); }
.dep-dates span { font-size: 11px; color: var(--text-secondary); }
.dep-prices { display: flex; flex-direction: column; align-items: flex-end; }
.dep-prices .adult { font-size: 13px; font-weight: 700; color: var(--theme-blue); }
.dep-prices .child { font-size: 11px; color: var(--text-secondary); }
.dep-prices small { font-size: 9px; color: var(--text-tertiary); }
</style>
