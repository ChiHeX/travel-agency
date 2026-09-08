export const orderStatusLabels = {
  WAIT_PAY: '待支付',
  PAID_WAIT_CONFIRM: '待确认',
  CONFIRMED: '已确认',
  TRAVELLING: '行程中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUND_APPLYING: '退款审核中',
  REFUND_PROCESSING: '退款处理中',
  REFUNDED: '已退款',
  REFUND_REJECTED: '退款未通过'
}

export const paymentStatusLabels = {
  UNPAID: '未支付',
  PENDING: '处理中',
  PAID: '已支付',
  FAILED: '支付失败',
  CLOSED: '已关闭',
  REFUNDED: '已退款'
}

export const refundStatusLabels = {
  APPLYING: '审核中',
  PROCESSING: '退款处理中',
  REFUNDED: '已退款',
  REJECTED: '审核未通过'
}

export const genderLabels = { MALE: '男', FEMALE: '女', OTHER: '其他' }
export const idTypeLabels = {
  CHINESE_ID_CARD: '身份证',
  PASSPORT: '护照',
  OTHER: '其他证件'
}

export function createIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function isSafePaymentUrl(value) {
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol)
  } catch {
    return false
  }
}
