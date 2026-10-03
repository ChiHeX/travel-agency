// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import RouteItinerary from '../RouteItinerary.vue'

/**
 * 用户端每日行程"当晚住宿"一节的契约测试（契约 {@code ItineraryDay}）。
 *
 * <p>重点不是文案好不好看，而是四条会直接误导用户的规则：</p>
 * <ol>
 *   <li><b>详情入口只在 {@code day.hotel} 非空时给出</b>：酒店被停用 / 已删除时摘要为 {@code null}，
 *       而 {@code GET /routes/{routeId}/hotels/{hotelId}} 对这类酒店一律 404 ——
 *       无条件加链接等于给用户一个必然报错的入口。行程仍安排了这家酒店，名称要照常显示；</li>
 *   <li>{@code accommodationType} 决定文案：{@code NONE} 是"当天不含住宿"，不能被渲染成
 *       "住宿安排暂未提供"，{@code STANDARD} / {@code PENDING} 也各有各的说法；</li>
 *   <li>官方星级只在是数字时显示，且必须标明是官方星级（本项目没有酒店评价体系）；</li>
 *   <li>{@code breakfastIncluded} 的 {@code false} 要显示"不含早餐"，{@code null} 整项不显示。</li>
 * </ol>
 *
 * <p>用真实 router（memory history）而不是 stub：链接的 href 正是这里要断言的东西。</p>
 */
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
  // 默认展开第一天，住宿一节就在展开区域里
  const wrapper = mount(RouteItinerary, {
    props: { days: days.map((day) => ({ items: [], ...day })), routeId },
    global: { plugins: [router] }
  })
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 契约 HotelSummary（行程内嵌的酒店摘要，不含 images）。 */
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
    // 封面图必须有 alt：既为读屏，也为图片加载失败时说明这是什么
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
    // 摘要为 null 的封面不会被编出来
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

  /**
   * 酒店被停用 / 已删除：`hotel` 摘要是 null，详情端点也会 404。
   * 名称是行程自身的事实，必须照常显示；链接必须消失，并说明没有公开详情页。
   */
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
    // 关键断言：没有详情链接（否则用户点进一个必然 404 的页面）
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
    // 不含住宿不等于"暂未提供"，不能被渲染成还没安排的口气
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
