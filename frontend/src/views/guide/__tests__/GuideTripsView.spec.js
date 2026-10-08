// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import GuideTripsView from '../GuideTripsView.vue'

/**
 * 导游带团详情页的状态操作入口（C-10）。
 *
 * <p>此前 `guideApi.start` 只存在于 `api/modules.js`，没有任何页面调用它，
 * 导游在界面上无法开始行程 —— 团期永远进不了 TRAVELLING。这里钉住三件事：</p>
 * <ol>
 *   <li>按钮出现的状态与后端 `DepartureService#STARTABLE_STATUSES` 一致
 *       （OPEN / FULL / CLOSED 才能开始，DRAFT / FINISHED / CANCELLED 不出现）；</li>
 *   <li>点击后确实调用契约接口 `POST /guide/departures/{id}/start`，并重新拉取详情；</li>
 *   <li>失败时提示服务端错误，且重复点击不会重复发请求。</li>
 * </ol>
 */

const { api, message } = vi.hoisted(() => ({
  api: { detail: vi.fn(), passengers: vi.fn(), departures: vi.fn(), start: vi.fn(), complete: vi.fn() },
  message: { success: vi.fn(), error: vi.fn() }
}))

vi.mock('@/api/modules', () => ({ guideApi: api }))
vi.mock('element-plus', () => ({ ElMessage: message }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: '42' } }) }))

// 用一个能发出 current-change 的桩替代真实分页组件，避免测试依赖完整 Element Plus。
const ElPaginationStub = {
  name: 'ElPagination',
  props: ['currentPage', 'pageSize', 'total'],
  emits: ['current-change'],
  template: '<div class="el-pagination-stub" />'
}

const DEPARTURE_ID = '42'

function departureDetail(status, overrides = {}) {
  return {
    departure: { id: DEPARTURE_ID, status, routeId: '7', startDate: '2026-10-20', endDate: '2026-10-25', ...overrides },
    route: { name: '云南 6 日', departureCity: '上海', destination: '云南', durationDays: 6 },
    itinerary: []
  }
}

const globalMountOptions = {
  stubs: { ElSkeleton: true, RouterLink: { template: '<a><slot /></a>' } },
  components: { ElPagination: ElPaginationStub }
}

async function open(status) {
  api.detail.mockResolvedValue(departureDetail(status))
  api.passengers.mockResolvedValue([])
  const wrapper = mount(GuideTripsView, {
    props: { detail: true },
    global: globalMountOptions
  })
  await flushPromises()
  return wrapper
}

const buttonByText = (wrapper, text) => wrapper.findAll('button').find(button => button.text() === text)

beforeEach(() => {
  vi.resetAllMocks()
})

it.each(['OPEN', 'FULL', 'CLOSED'])('offers 开始行程 while the departure is %s', async status => {
  const wrapper = await open(status)

  expect(buttonByText(wrapper, '开始行程')).toBeTruthy()
  expect(buttonByText(wrapper, '标记行程已结束')).toBeUndefined()
})

it.each(['DRAFT', 'FINISHED', 'CANCELLED'])('hides every action while the departure is %s', async status => {
  const wrapper = await open(status)

  expect(buttonByText(wrapper, '开始行程')).toBeUndefined()
  expect(buttonByText(wrapper, '标记行程已结束')).toBeUndefined()
})

it('offers only 标记行程已结束 while the departure is TRAVELLING', async () => {
  const wrapper = await open('TRAVELLING')

  expect(buttonByText(wrapper, '开始行程')).toBeUndefined()
  expect(buttonByText(wrapper, '标记行程已结束')).toBeTruthy()
})

it('calls the contract endpoint, reports success and reloads the detail', async () => {
  const wrapper = await open('OPEN')
  // 开始成功后服务端状态推进为 TRAVELLING，页面据此换成「标记行程已结束」。
  api.start.mockResolvedValue({ id: DEPARTURE_ID, status: 'TRAVELLING' })
  api.detail.mockResolvedValue(departureDetail('TRAVELLING'))

  await buttonByText(wrapper, '开始行程').trigger('click')
  await flushPromises()

  expect(api.start).toHaveBeenCalledExactlyOnceWith(DEPARTURE_ID)
  expect(api.complete).not.toHaveBeenCalled()
  expect(message.success).toHaveBeenCalled()
  expect(message.error).not.toHaveBeenCalled()
  expect(api.detail).toHaveBeenCalledTimes(2)
  expect(buttonByText(wrapper, '开始行程')).toBeUndefined()
  expect(buttonByText(wrapper, '标记行程已结束')).toBeTruthy()
})

it('surfaces the server error and stays on the same status', async () => {
  const wrapper = await open('OPEN')
  api.start.mockRejectedValue(new Error('当前团期状态不允许开始行程：DRAFT'))

  await buttonByText(wrapper, '开始行程').trigger('click')
  await flushPromises()

  expect(message.error).toHaveBeenCalledWith('当前团期状态不允许开始行程：DRAFT')
  expect(message.success).not.toHaveBeenCalled()
  expect(buttonByText(wrapper, '开始行程')).toBeTruthy()
})

it('ignores repeated clicks while the request is in flight', async () => {
  let resolve
  const wrapper = await open('OPEN')
  api.start.mockReturnValue(new Promise(r => { resolve = r }))

  const button = buttonByText(wrapper, '开始行程')
  await button.trigger('click')
  await button.trigger('click')

  expect(api.start).toHaveBeenCalledOnce()
  resolve({ id: DEPARTURE_ID, status: 'TRAVELLING' })
  await flushPromises()
  expect(api.start).toHaveBeenCalledOnce()
})

it('completes a travelling departure through the same wiring', async () => {
  const wrapper = await open('TRAVELLING')
  api.complete.mockResolvedValue({ id: DEPARTURE_ID, status: 'FINISHED' })
  api.detail.mockResolvedValue(departureDetail('FINISHED'))

  await buttonByText(wrapper, '标记行程已结束').trigger('click')
  await flushPromises()

  expect(api.complete).toHaveBeenCalledExactlyOnceWith(DEPARTURE_ID)
  expect(api.start).not.toHaveBeenCalled()
  expect(buttonByText(wrapper, '标记行程已结束')).toBeUndefined()
})

/**
 * 「我的团期」列表（非详情）的分页与分类筛选。
 *
 * <p>此前列表调用 `guideApi.departures()` 不带任何参数，后端按默认 page=1/size=20 返回，
 * 页面上既没有翻页入口、也没有 UPCOMING/CURRENT/HISTORY 分类，第 21 条之后的团期在界面上
 * 永远看不到。这里钉住三件事：带分页参数请求、按 scope 筛选并回到第一页、以及能翻页。</p>
 */

const SAMPLE_TRIPS = [
  { id: '1', routeId: '7', routeName: '云南 6 日', startDate: '2026-10-20', endDate: '2026-10-25', status: 'OPEN' }
]

function tripPage(items, overrides = {}) {
  return { items, page: 1, size: 9, total: items.length, totalPages: 1, ...overrides }
}

function mountList() {
  return mount(GuideTripsView, {
    props: { detail: false },
    global: globalMountOptions
  })
}

async function openList(response) {
  api.departures.mockResolvedValue(response)
  const wrapper = mountList()
  await flushPromises()
  return wrapper
}

it('loads 我的团期 with explicit pagination params and renders the route name', async () => {
  const wrapper = await openList(tripPage(SAMPLE_TRIPS, { total: 25, totalPages: 3 }))

  expect(api.departures).toHaveBeenCalledWith({ page: 1, size: 9 })
  expect(wrapper.text()).toContain('云南 6 日')
})

it('filters by scope and resets to the first page', async () => {
  const wrapper = await openList(tripPage(SAMPLE_TRIPS))

  api.departures.mockResolvedValue(tripPage([]))
  await buttonByText(wrapper, '待出发').trigger('click')
  await flushPromises()

  expect(api.departures).toHaveBeenLastCalledWith({ scope: 'UPCOMING', page: 1, size: 9 })
})

it('pages through the list through the pagination control', async () => {
  const wrapper = await openList(tripPage(SAMPLE_TRIPS, { total: 25, totalPages: 3 }))
  const pagination = wrapper.findComponent(ElPaginationStub)
  expect(pagination.exists()).toBe(true)

  api.departures.mockResolvedValue(tripPage(SAMPLE_TRIPS, { page: 2, total: 25, totalPages: 3 }))
  pagination.vm.$emit('current-change', 2)
  await flushPromises()

  expect(api.departures).toHaveBeenLastCalledWith({ page: 2, size: 9 })
})

it('hides the pagination control when all trips fit on one page', async () => {
  const wrapper = await openList(tripPage(SAMPLE_TRIPS))

  expect(wrapper.findComponent(ElPaginationStub).exists()).toBe(false)
})

it('surfaces a load failure on the 我的团期 list', async () => {
  api.departures.mockRejectedValue(new Error('团期服务不可用'))
  const wrapper = mountList()
  await flushPromises()

  expect(wrapper.text()).toContain('团期服务不可用')
})
