// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MessagesView from '../MessagesView.vue'

const { api, refreshUnread } = vi.hoisted(() => ({
  api: { messages: vi.fn(), readMessage: vi.fn(), readAllMessages: vi.fn() },
  refreshUnread: vi.fn()
}))
vi.mock('@/api/modules', () => ({ accountApi: api }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() } }))

const message = { id: '1', title: '报名确认', content: '你的报名已确认', read: false, createdAt: '2026-10-04' }
beforeEach(() => {
  vi.resetAllMocks()
  api.messages.mockResolvedValue({ items: [{ ...message }, { ...message, id: '2', read: true }], total: 2 })
  api.readMessage.mockResolvedValue({ ...message, read: true })
})
async function open() {
  const wrapper = mount(MessagesView, { global: {
    provide: { refreshUnread },
    stubs: {
      ElSkeleton: true, ElPagination: true,
      ElCheckbox: {
        props: ['modelValue'], emits: ['update:modelValue', 'change'],
        template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked); $emit(\'change\', $event.target.checked)" />'
      }
    }
  } })
  await flushPromises()
  return wrapper
}

it('clicking message content marks only that message read and refreshes the unread count', async () => {
  const wrapper = await open()
  await wrapper.get('.msg-body-text').trigger('click')
  await flushPromises()
  expect(api.readMessage).toHaveBeenCalledExactlyOnceWith('1')
  expect(refreshUnread).toHaveBeenCalledOnce()
  expect(wrapper.findAll('.message-card-item')[0].classes()).not.toContain('unread')
  expect(api.readAllMessages).not.toHaveBeenCalled()
  await wrapper.findAll('.message-card-item')[0].trigger('click')
  await wrapper.findAll('.message-card-item')[1].trigger('click')
  expect(api.readMessage).toHaveBeenCalledTimes(1)
})

it.each([
  ['ORDER_AUDIT_ANOMALY', '报名审核异常'],
  ['DEPARTURE_REMINDER', '即将出发提醒'],
  ['DEPARTURE_STATUS', '团期状态变化']
])('displays and marks %s notifications read', async (type, title) => {
  api.messages.mockResolvedValue({ items: [{ ...message, type, title, content: `${title}测试正文` }], total: 1 })
  const wrapper = await open()
  expect(wrapper.text()).toContain(`${title}测试正文`)
  await wrapper.get('.message-card-item').trigger('click')
  await flushPromises()
  expect(refreshUnread).toHaveBeenCalledOnce()
  expect(wrapper.get('.message-card-item').classes()).not.toContain('unread')
  wrapper.unmount()
})

it('does not let an older unread filter response replace the latest list', async () => {
  const wrapper = await open()
  let resolve
  api.messages.mockReturnValueOnce(new Promise(r => { resolve = r }))
  await wrapper.get('input[type="checkbox"]').setValue(true)
  api.messages.mockResolvedValueOnce({ items: [{ ...message, title: '最新消息' }], total: 1 })
  await wrapper.get('input[type="checkbox"]').setValue(false)
  await flushPromises()
  resolve({ items: [{ ...message, title: '旧消息' }], total: 1 })
  await flushPromises()
  expect(wrapper.text()).toContain('最新消息')
  expect(wrapper.text()).not.toContain('旧消息')
  wrapper.unmount()
})

it.each(['Enter', ' '])('supports marking a focused message read with %s', async key => {
  const wrapper = await open()
  const card = wrapper.get('.message-card-item.unread')
  expect(card.attributes('tabindex')).toBe('0')
  await card.trigger('keydown', { key })
  await flushPromises()
  expect(api.readMessage).toHaveBeenCalledWith('1')
})

it('ignores repeated clicks while marking the message read', async () => {
  let resolve
  api.readMessage.mockReturnValue(new Promise(r => { resolve = r }))
  const wrapper = await open()
  const card = wrapper.get('.message-card-item.unread')
  await card.trigger('click')
  await card.trigger('click')
  expect(api.readMessage).toHaveBeenCalledTimes(1)
  expect(card.classes()).toContain('unread')
  resolve({ ...message, read: true })
  await flushPromises()
  expect(card.classes()).not.toContain('unread')
})

it('keeps a failed read unread and lets the user retry after reloading', async () => {
  api.readMessage.mockRejectedValueOnce(new Error('标记失败'))
  const wrapper = await open()
  await wrapper.get('.message-card-item.unread').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('标记失败')
  expect(refreshUnread).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(button => button.text() === '重新加载').trigger('click')
  await flushPromises()
  expect(wrapper.get('.message-card-item').classes()).toContain('unread')
  await wrapper.get('.message-card-item.unread').trigger('click')
  await flushPromises()
  expect(api.readMessage).toHaveBeenCalledTimes(2)
})

it('reloads the unread-only list after reading a message', async () => {
  const wrapper = await open()
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await flushPromises()
  api.messages.mockResolvedValue({ items: [], total: 0 })
  await wrapper.get('.message-card-item.unread').trigger('click')
  await flushPromises()
  expect(api.messages).toHaveBeenLastCalledWith({ page: 1, size: 10, unreadOnly: true })
  expect(wrapper.find('.message-card-item').exists()).toBe(false)
})
