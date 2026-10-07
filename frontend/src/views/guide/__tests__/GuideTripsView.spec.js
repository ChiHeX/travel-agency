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

const DEPARTURE_ID = '42'

function departureDetail(status, overrides = {}) {
  return {
    departure: { id: DEPARTURE_ID, status, routeId: '7', startDate: '2026-10-20', endDate: '2026-10-25', ...overrides },
    route: { name: '云南 6 日', departureCity: '上海', destination: '云南', durationDays: 6 },
    itinerary: []
  }
}

async function open(status) {
  api.detail.mockResolvedValue(departureDetail(status))
  api.passengers.mockResolvedValue([])
  const wrapper = mount(GuideTripsView, {
    props: { detail: true },
    global: { stubs: { ElSkeleton: true, RouterLink: { template: '<a><slot /></a>' } } }
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
