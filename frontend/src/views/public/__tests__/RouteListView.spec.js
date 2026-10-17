// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RouteListView from '../RouteListView.vue'

const { api, route, replace } = vi.hoisted(() => ({ api: { list: vi.fn() }, route: { query: {} }, replace: vi.fn() }))
vi.mock('@/api/modules', () => ({ routeApi: api }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace, push: vi.fn() }) }))
beforeEach(() => { vi.resetAllMocks(); route.query = {}; api.list.mockResolvedValue({ items: [], total: 0 }) })
const open = () => mount(RouteListView, { global: { stubs: { AppIcon: true, PanelIconButton: true, ElPagination: true } } })

it.each([{}, { destination: '杭州' }, { departureCity: '上海' }, { minPrice: '100', maxPrice: '500' }, { durationDays: '3' }, { departureMonth: '10' }, { destination: '杭州', durationDays: '3', departureMonth: '10' }])('requests independent and combined filters: %j', async query => {
  route.query = query
  const wrapper = open()
  await flushPromises()
  expect(api.list).toHaveBeenCalledOnce()
  expect(api.list.mock.calls[0][0]).toMatchObject(query)
  expect(wrapper.text()).toContain('可选线路')
  wrapper.unmount()
})

it('ignores an older response after a new destination filter', async () => {
  let resolve
  api.list.mockReturnValueOnce(new Promise(r => { resolve = r }))
  const wrapper = open()
  await wrapper.get('#route-destination').setValue('杭州')
  api.list.mockResolvedValueOnce({ items: [{ id: '2', name: '新线路', destination: '杭州' }], total: 1 })
  await wrapper.get('#route-destination').trigger('change')
  await flushPromises()
  resolve({ items: [{ id: '1', name: '旧线路' }], total: 1 })
  await flushPromises()
  expect(wrapper.text()).toContain('新线路')
  expect(wrapper.text()).not.toContain('旧线路')
  wrapper.unmount()
})
