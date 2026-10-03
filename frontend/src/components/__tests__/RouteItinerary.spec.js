// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import RouteItinerary from '../RouteItinerary.vue'

const ROUTE_ID = '7'

async function mountItinerary(days, routeId = ROUTE_ID) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/routes/:id', name: 'route-detail', component: { template: '<div />' } },
      { path: '/routes/:routeId/hotels/:hotelId', name: 'hotel-detail', component: { template: '<div />' } }
    ]
  })
  await router.push(`/routes/${routeId}`)
  await router.isReady()
  const wrapper = mount(RouteItinerary, {
    props: { days: days.map((day) => ({ items: [], ...day })), routeId },
    global: { plugins: [router] }
  })
  await wrapper.vm.$nextTick()
  return wrapper
}

const hotelSummary = {
  id: '31',
  name: '杭州湖畔演示酒店',
  city: '杭州',
  address: '浙江省杭州市西湖区湖畔路 1 号',
  coverUrl: 'https://example.com/hotel-31-cover.jpg',
  starRating: 4
}

describe('RouteItinerary 当晚住宿：指定酒店（HOTEL）', () => {
  it('渲染封面、名称、城市与地址、官方星级，并链接到酒店详情路由', async () => {
    const wrapper = await mountItinerary([{
      id: '71',
      dayNumber: 1,
      title: '上海 → 杭州',
      accommodationType: 'HOTEL',
      hotelId: '31',
      hotelName: '杭州湖畔演示酒店',
      hotel: hotelSummary,
      roomType: '双床房',
      breakfastIncluded: true,
      accommodationNote: '如遇满房将安排同级别酒店'
    }])

    const cover = wrapper.find('.hotel-cover')
    expect(cover.exists()).toBe(true)
    expect(cover.attributes('src')).toBe('https://example.com/hotel-31-cover.jpg')
    expect(cover.attributes('alt')).toBe('杭州湖畔演示酒店')
    expect(wrapper.find('.hotel-placeholder').exists()).toBe(false)

    const text = wrapper.text()
    expect(text).toContain('杭州湖畔演示酒店')
    expect(text).toContain('杭州 · 浙江省杭州市西湖区湖畔路 1 号')
    expect(text).toContain('官方星级 4 星')
    expect(text).toContain('房型：双床房')
    expect(text).toContain('含早餐')
    expect(text).toContain('如遇满房将安排同级别酒店')

    const link = wrapper.find('.hotel-link')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/routes/7/hotels/31')
  })

  it('coverUrl 为空时显示占位块，不回退到详情图片（摘要里本来就没有 images）', async () => {
    const wrapper = await mountItinerary([{
      id: '71',
      dayNumber: 1,
      title: '上海 → 杭州',
      accommodationType: 'HOTEL',
      hotelId: '31',
      hotelName: '杭州湖畔演示酒店',
      hotel: { ...hotelSummary, coverUrl: null }
    }])

    expect(wrapper.find('.hotel-placeholder').exists()).toBe(true)
    expect(wrapper.find('.hotel-cover').exists()).toBe(false)
    expect(wrapper.find('.hotel-card img').exists()).toBe(false)
  })

  it('starRating 为 null 时不显示星级，也不打印 null', async () => {
    const wrapper = await mountItinerary([{
      id: '71',
      dayNumber: 1,
      title: '上海 → 杭州',
      accommodationType: 'HOTEL',
      hotelId: '31',
      hotelName: '杭州湖畔演示酒店',
      hotel: { ...hotelSummary, starRating: null }
    }])

    expect(wrapper.text()).not.toContain('官方星级')
    expect(wrapper.text()).not.toContain('null')
    expect(wrapper.text()).not.toContain('undefined')
  })

  it('酒店摘要为 null 但行程仍安排了酒店时：显示名称、不给详情链接、给出说明', async () => {
    const wrapper = await mountItinerary([{
      id: '71',
      dayNumber: 1,
      title: '上海 → 杭州',
      accommodationType: 'HOTEL',
      hotelId: '31',
      hotelName: '已停止合作的演示酒店',
      hotel: null
    }])

    const text = wrapper.text()
    expect(text).toContain('已停止合作的演示酒店')
    expect(text).toContain('没有可公开的资料页')
    expect(wrapper.find('.hotel-link').exists()).toBe(false)
    expect(wrapper.find('a').exists()).toBe(false)
    expect(text).not.toContain('null')
    expect(text).not.toContain('undefined')
  })
})

describe('RouteItinerary 当晚住宿：其它住宿安排', () => {
  it('STANDARD：显示住宿标准、房型、早餐与说明，且没有酒店链接', async () => {
    const wrapper = await mountItinerary([{
      id: '72',
      dayNumber: 2,
      title: '千岛湖',
      accommodationType: 'STANDARD',
      accommodationStandard: '市区舒适型酒店',
      roomType: '大床房',
      breakfastIncluded: false,
      accommodationNote: '以出团通知为准'
    }])

    const text = wrapper.text()
    expect(text).toContain('只确定住宿标准')
    expect(text).toContain('住宿标准：市区舒适型酒店')
    expect(text).toContain('房型：大床房')
    expect(text).toContain('不含早餐')
    expect(text).toContain('以出团通知为准')
    expect(wrapper.find('.hotel-link').exists()).toBe(false)
  })

  it('NONE：明确说明当天不含住宿，并带上说明', async () => {
    const wrapper = await mountItinerary([{
      id: '73',
      dayNumber: 3,
      title: '夜车返程',
      accommodationType: 'NONE',
      accommodationNote: '当晚夜车返程，不含住宿'
    }])

    const text = wrapper.text()
    expect(text).toContain('当天不含住宿')
    expect(text).toContain('当晚夜车返程，不含住宿')
    expect(text).not.toContain('住宿安排暂未提供')
    expect(wrapper.find('.hotel-link').exists()).toBe(false)
  })

  it('PENDING：说明住宿尚未确认，并带上说明', async () => {
    const wrapper = await mountItinerary([{
      id: '74',
      dayNumber: 4,
      title: '丽江',
      accommodationType: 'PENDING',
      accommodationNote: '待地接确认后补充'
    }])

    const text = wrapper.text()
    expect(text).toContain('住宿待确认')
    expect(text).toContain('待地接确认后补充')
    expect(wrapper.find('.hotel-link').exists()).toBe(false)
  })
})

describe('RouteItinerary 折叠标题的住宿摘要', () => {
  it('指定酒店时用行程自己的 hotelName', async () => {
    const wrapper = await mountItinerary([{
      id: '71',
      dayNumber: 1,
      title: '上海 → 杭州',
      accommodationType: 'HOTEL',
      hotelId: '31',
      hotelName: '杭州湖畔演示酒店',
      hotel: hotelSummary
    }])

    expect(wrapper.find('.day-heading-copy > span').text()).toContain('杭州湖畔演示酒店')
  })

  it('没有酒店时按住宿安排给出说明：NONE 读作"不含住宿"，而不是"暂未提供"', async () => {
    const wrapper = await mountItinerary([{
      id: '73',
      dayNumber: 3,
      title: '夜车返程',
      accommodationType: 'NONE'
    }])

    const summary = wrapper.find('.day-heading-copy > span').text()
    expect(summary).toContain('当天不含住宿')
    expect(summary).not.toContain('住宿安排暂未提供')
  })
})

describe('RouteItinerary 早餐三态', () => {
  it('breakfastIncluded 为 null 时不渲染任何早餐文案', async () => {
    const wrapper = await mountItinerary([{
      id: '71',
      dayNumber: 1,
      title: '上海 → 杭州',
      accommodationType: 'HOTEL',
      hotelId: '31',
      hotelName: '杭州湖畔演示酒店',
      hotel: hotelSummary,
      breakfastIncluded: null
    }])

    expect(wrapper.text()).not.toContain('含早餐')
    expect(wrapper.text()).not.toContain('不含早餐')
  })

  it('显式 false 渲染"不含早餐"，与未说明明确区分', async () => {
    const wrapper = await mountItinerary([{
      id: '71',
      dayNumber: 1,
      title: '上海 → 杭州',
      accommodationType: 'HOTEL',
      hotelId: '31',
      hotelName: '杭州湖畔演示酒店',
      hotel: hotelSummary,
      breakfastIncluded: false
    }])

    expect(wrapper.text()).toContain('不含早餐')
  })
})

describe('RouteItinerary 集中住宿安排', () => {
  const day = (number, overrides = {}) => ({
    id: String(number), dayNumber: number, title: '行程 ' + number,
    accommodationType: 'HOTEL', hotelId: '31', hotelName: hotelSummary.name,
    hotel: hotelSummary, roomType: '双床房', breakfastIncluded: true, ...overrides
  })

  it('连续入住只显示一张卡片，保留不同日期的房型与早餐，不含住宿不计入晚数', async () => {
    const wrapper = await mountItinerary([
      day(1), day(2), day(3, { roomType: '大床房', breakfastIncluded: false }),
      day(4, { accommodationType: 'NONE', hotelId: null, hotelName: null, hotel: null })
    ])
    expect(wrapper.findAll('.hotel-card')).toHaveLength(1)
    expect(wrapper.find('.stay-period').text()).toContain('第 1–3 晚')
    expect(wrapper.find('.stay-period').text()).toContain('3 晚')
    expect(wrapper.findAll('.stay-details')).toHaveLength(2)
    expect(wrapper.find('.stay-overview').text()).toContain('第 1–2 晚')
    expect(wrapper.find('.stay-overview').text()).toContain('大床房')
    expect(wrapper.find('.stay-overview').text()).toContain('不含早餐')
    await wrapper.find('.itinerary-toolbar button').trigger('click')
    const days = wrapper.findAll('.itinerary-day')
    expect(days[1].find('.daily-accommodation').text()).toContain('续住 ' + hotelSummary.name)
    expect(days[3].find('.daily-accommodation').text()).toContain('当天不含住宿')
    expect(wrapper.findAll('.day-body .hotel-card')).toHaveLength(0)
  })

  it('换酒店或中断入住后分别展示；最后一天的实际酒店安排仍保留', async () => {
    const wrapper = await mountItinerary([
      day(1), day(2, { hotelId: '32', hotel: { ...hotelSummary, id: '32' } }),
      day(3), day(5)
    ])
    expect(wrapper.findAll('.stay-card')).toHaveLength(4)
    expect(wrapper.findAll('.stay-period')[3].text()).toContain('第 5 晚')
    expect(wrapper.findAll('.day-heading-copy > span')[2].text()).not.toContain('续住')
  })
})


it('待确认住宿仍展示已确定的房型、住宿标准和早餐', async () => {
  const wrapper = await mountItinerary([{ id: '1', dayNumber: 1, title: '第一天',
    accommodationType: 'PENDING', accommodationStandard: '市区酒店', roomType: '双床房', breakfastIncluded: false }])
  const text = wrapper.find('.daily-accommodation').text()
  expect(text).toContain('住宿待确认')
  expect(text).toContain('市区酒店')
  expect(text).toContain('双床房')
  expect(text).toContain('不含早餐')
})
