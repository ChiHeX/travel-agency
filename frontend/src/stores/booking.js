import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { createIdempotencyKey } from '@/utils/order'

export const useBookingStore = defineStore('booking', () => {
  // Full traveler details stay in memory, never in browser storage or URLs.
  const drafts = ref({})
  const auth = useAuthStore()
  watch(() => auth.token, () => { drafts.value = {} }, { flush: 'sync' })

  function open(id, routeId, form) {
    const existing = drafts.value[id]
    if (existing && existing.routeId === routeId) return existing
    const draftId = createIdempotencyKey()
    drafts.value[draftId] = {
      id: draftId, routeId, form, order: null, submittedForm: '',
      createOrderKey: createIdempotencyKey()
    }
    return drafts.value[draftId]
  }

  return { drafts, open }
})
