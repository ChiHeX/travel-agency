// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DepartureFormDialog from '../DepartureFormDialog.vue'

/**
 * 团期修改的乐观锁前端行为测试。
 *
 * <p>覆盖的是评审指出的一个真实回归：冲突面板里点「载入服务器最新数据」之后，
 * 表单的<b>基准版本</b>必须一起推进到服务端那一份的版本。否则用户载入最新数据、
 * 再改一处并保存时，提交的仍是打开表单时的旧版本，会立刻再撞一次同样的冲突 ——
 * 表现为"点了载入最新数据也没用"。</p>
 */

const updateDeparture = vi.fn()
const createDeparture = vi.fn()
const fetchDeparture = vi.fn()
const fetchRoutes = vi.fn()
const fetchGuides = vi.fn()
const fetchRoute = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    updateDeparture: (...args) => updateDeparture(...args),
    createDeparture: (...args) => createDeparture(...args),
    departure: (...args) => fetchDeparture(...args),
    routes: (...args) => fetchRoutes(...args),
    guides: (...args) => fetchGuides(...args),
    route: (...args) => fetchRoute(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

const VERSION_ON_OPEN = 3
const VERSION_ON_SERVER = 9

const departureOnOpen = {
  id: '1',
  routeId: '10',
  routeName: '云南 6 日',
  startDate: '2026-10-01',
  endDate: '2026-10-06',
  adultPrice: '2999.00',
  childPrice: '1999.00',
  maxPeople: 30,
  reservedPeople: 0,
  confirmedPeople: 0,
  availableSeats: 30,
  guideId: null,
  guideName: null,
  status: 'DRAFT',
  version: VERSION_ON_OPEN
}

/** 他人已经改过的那一份：上限 25，版本 9。 */
const departureOnServer = { ...departureOnOpen, maxPeople: 25, version: VERSION_ON_SERVER }

function versionConflict() {
  return Object.assign(new Error('团期已被他人修改'), {
    status: 409,
    code: 'DEPARTURE_VERSION_CONFLICT'
  })
}

function mountDialog() {
  return mount(DepartureFormDialog, {
    props: { modelValue: true, departure: departureOnOpen },
    global: {
      // el-dialog / el-skeleton 由应用全局注册，测试里只保留插槽内容。
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-skeleton': true
      }
    }
  })
}

function buttonByText(wrapper, text) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${wrapper.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

function maxPeopleInput(wrapper) {
  return wrapper.findAll('input[type="number"]')[0]
}

beforeEach(() => {
  updateDeparture.mockReset()
  createDeparture.mockReset()
  fetchDeparture.mockReset()
  fetchRoutes.mockReset().mockResolvedValue({ items: [], total: 0 })
  fetchGuides.mockReset().mockResolvedValue({ items: [], total: 0 })
  fetchRoute.mockReset().mockResolvedValue(null)
})

describe('DepartureFormDialog 乐观锁', () => {
  it('冲突后载入服务器最新数据，再次保存使用服务端版本并成功', async () => {
    updateDeparture.mockRejectedValueOnce(versionConflict()).mockResolvedValueOnce(departureOnServer)
    fetchDeparture.mockResolvedValue(departureOnServer)

    const wrapper = mountDialog()
    await flushPromises()

    // ① 保存触发冲突：提交的是打开表单时拿到的版本。
    await buttonByText(wrapper, '保存团期').trigger('click')
    await flushPromises()

    expect(updateDeparture).toHaveBeenCalledTimes(1)
    expect(updateDeparture.mock.calls[0][1].version).toBe(VERSION_ON_OPEN)
    expect(fetchDeparture).toHaveBeenCalledWith('1')
    expect(wrapper.text()).toContain('团期已被他人修改')

    // ② 载入服务器最新数据：表单被替换成服务端那一份。
    await buttonByText(wrapper, '载入服务器最新数据').trigger('click')
    await flushPromises()

    expect(maxPeopleInput(wrapper).element.value).toBe('25')

    // ③ 在最新数据上再改一处并保存：这次必须用服务端版本，而且是回归点 —— 用旧版本会再次冲突。
    await maxPeopleInput(wrapper).setValue('28')
    await buttonByText(wrapper, '保存团期').trigger('click')
    await flushPromises()

    expect(updateDeparture).toHaveBeenCalledTimes(2)
    const secondPayload = updateDeparture.mock.calls[1][1]
    expect(secondPayload.version).toBe(VERSION_ON_SERVER)
    expect(secondPayload.maxPeople).toBe(28)
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('选择保留我的修改并覆盖时，也用服务端版本重试', async () => {
    updateDeparture.mockRejectedValueOnce(versionConflict()).mockResolvedValueOnce(departureOnServer)
    fetchDeparture.mockResolvedValue(departureOnServer)

    const wrapper = mountDialog()
    await flushPromises()

    await buttonByText(wrapper, '保存团期').trigger('click')
    await flushPromises()

    // 用户不改表单，直接选择覆盖：表单里仍是他自己的值（30），版本换成服务端的最新版本。
    await buttonByText(wrapper, '保留我的修改并覆盖').trigger('click')
    await flushPromises()

    expect(updateDeparture).toHaveBeenCalledTimes(2)
    const retryPayload = updateDeparture.mock.calls[1][1]
    expect(retryPayload.version).toBe(VERSION_ON_SERVER)
    expect(retryPayload.maxPeople).toBe(30)
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('载入最新数据后关闭并重新打开，基准版本回到当前这条团期的版本', async () => {
    updateDeparture.mockRejectedValueOnce(versionConflict()).mockResolvedValueOnce(departureOnServer)
    fetchDeparture.mockResolvedValue(departureOnServer)

    const wrapper = mountDialog()
    await flushPromises()

    await buttonByText(wrapper, '保存团期').trigger('click')
    await flushPromises()
    await buttonByText(wrapper, '载入服务器最新数据').trigger('click')
    await flushPromises()

    // 关闭再打开（父组件重新传入同一条团期），表单必须回到传入的那一份，而不是残留服务端版本。
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(maxPeopleInput(wrapper).element.value).toBe('30')

    updateDeparture.mockResolvedValueOnce(departureOnServer)
    await buttonByText(wrapper, '保存团期').trigger('click')
    await flushPromises()

    expect(updateDeparture.mock.calls.at(-1)[1].version).toBe(VERSION_ON_OPEN)
  })

  /**
   * 修改请求会提交全部可编辑字段（含线路与导游），所以冲突面板必须把它们也摆出来。
   * 只对比日期 / 价格 / 人数的话，别人改了线路或导游时，
   * 用户会在看不到差异的情况下点下「保留我的修改并覆盖」。
   */
  it('别人改了线路与导游时，冲突面板必须把这两项纳入对比并标记差异', async () => {
    const changedByOthers = {
      ...departureOnServer,
      routeId: '77',
      routeName: '另一条线路',
      guideId: '5',
      guideName: '李导'
    }
    updateDeparture.mockRejectedValueOnce(versionConflict())
    fetchDeparture.mockResolvedValue(changedByOthers)

    const wrapper = mountDialog()
    await flushPromises()

    await buttonByText(wrapper, '保存团期').trigger('click')
    await flushPromises()

    const text = wrapper.text()
    // 两项都在面板里，且服务端与用户填写的值都摆了出来。
    expect(text).toContain('所属线路')
    expect(text).toContain('带团导游')
    expect(text).toContain('另一条线路')
    expect(text).toContain('线路 #10')
    expect(text).toContain('李导')
    expect(text).toContain('暂不分配')
    // 差异被点名，用户点覆盖前就知道会盖掉什么。
    expect(text).toContain('所属线路、带团导游')
    expect(text).toContain('有差异')

    // 用户填写的内容仍然保留：选「覆盖」时提交的是自己那份线路 / 导游，且基于服务端最新版本。
    updateDeparture.mockResolvedValueOnce(changedByOthers)
    await buttonByText(wrapper, '保留我的修改并覆盖').trigger('click')
    await flushPromises()

    const payload = updateDeparture.mock.calls.at(-1)[1]
    expect(payload.routeId).toBe('10')
    expect(payload.guideId).toBe(null)
    expect(payload.version).toBe(VERSION_ON_SERVER)
  })

  it('只差人数时，差异清单不应把线路与导游也算进去', async () => {
    updateDeparture.mockRejectedValueOnce(versionConflict())
    fetchDeparture.mockResolvedValue({ ...departureOnOpen, maxPeople: 25, version: VERSION_ON_SERVER })

    const wrapper = mountDialog()
    await flushPromises()

    await buttonByText(wrapper, '保存团期').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('最大人数')
    expect(wrapper.text()).toContain('所属线路')
    expect(wrapper.text()).not.toContain('所属线路、带团导游')
  })
})
