// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PlaceGuideFormDialog from '../PlaceGuideFormDialog.vue'

/**
 * 地点指南表单的契约测试（契约 {@code PlaceGuideUpsertRequest}）。
 *
 * <p>覆盖这一档最容易出错的接线：</p>
 * <ul>
 *   <li>只提交契约字段（{@code title / summary / city / destination / coverUrl / places}），
 *       不上送 {@code id / status / authorId / publishedAt}（严格模式下就是 400）；</li>
 *   <li>{@code places} 的顺序就是地图顺序，提交时必须保持界面上的排序；</li>
 *   <li>{@code attractionId} 必须是字符串 id，可空字段（摘要/目的地/封面/备注）为空时提交 {@code null}；</li>
 *   <li>只有"启用且带经纬度"的景点才能作为地点（后端以 422 拒绝无坐标 / 已停用的地点），
 *       因此无坐标的景点不得出现在可选项里，<b>少于两个地点</b>必须在本地就被拦下。</li>
 * </ul>
 */

const createPlaceGuide = vi.fn()
const updatePlaceGuide = vi.fn()
const fetchAttractions = vi.fn()
const warning = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    createPlaceGuide: (...args) => createPlaceGuide(...args),
    updatePlaceGuide: (...args) => updatePlaceGuide(...args),
    attractions: (...args) => fetchAttractions(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: (...args) => warning(...args), error: vi.fn(), info: vi.fn() }
}))

const westLake = { id: '12', name: '西湖', city: '杭州', longitude: 120.13, latitude: 30.24, status: 'ACTIVE' }
const lingyin = { id: '13', name: '灵隐寺', city: '杭州', longitude: 120.1, latitude: 30.241, status: 'ACTIVE' }
/** 启用但没有坐标：不能作为指南地点，不得出现在下拉候选里。 */
const withoutCoordinates = { id: '14', name: '无坐标景点', city: '杭州', longitude: null, latitude: null, status: 'ACTIVE' }
/** 已停用：即使有坐标也不能被选为地点。 */
const disabledAttraction = { id: '15', name: '已停用景点', city: '杭州', longitude: 120.2, latitude: 30.25, status: 'DISABLED' }

function mountDialog(guide = null) {
  return mount(PlaceGuideFormDialog, {
    props: { modelValue: true, guide },
    global: {
      // el-dialog 由应用全局注册，测试里只保留插槽内容。
      stubs: { 'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' } }
    }
  })
}

/** 按 label 文案定位表单项里的控件。 */
function field(wrapper, label) {
  const group = wrapper.findAll('.form-field')
    .find((item) => item.find('label').exists() && item.find('label').text().startsWith(label))
  if (!group) throw new Error(`找不到标签以「${label}」开头的表单项`)
  const control = group.find('input, textarea, select')
  if (!control.exists()) throw new Error(`表单项「${label}」里没有输入控件`)
  return control
}

function buttonByText(wrapper, text) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${wrapper.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

beforeEach(() => {
  createPlaceGuide.mockReset()
  updatePlaceGuide.mockReset()
  warning.mockReset()
  fetchAttractions.mockReset().mockResolvedValue({
    items: [westLake, withoutCoordinates, lingyin, disabledAttraction],
    totalPages: 1
  })
})

describe('PlaceGuideFormDialog', () => {
  it('新增：只提交契约字段，地点顺序与 id 形状正确，空的可选字段提交 null', async () => {
    createPlaceGuide.mockResolvedValue({ id: '1', status: 'DRAFT' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '指南标题').setValue('  杭州双景点地图指南  ')
    await field(wrapper, '所属城市').setValue('杭州')

    const selects = wrapper.findAll('.place-select')
    expect(selects).toHaveLength(2)
    await selects[0].setValue('12')
    await selects[1].setValue('13')
    await wrapper.findAll('.place-note')[1].setValue(' 顺路可看飞来峰 ')
    await flushPromises()

    await buttonByText(wrapper, '保存指南').trigger('click')
    await flushPromises()

    expect(createPlaceGuide).toHaveBeenCalledTimes(1)
    const payload = createPlaceGuide.mock.calls[0][0]
    expect(payload).toEqual({
      title: '杭州双景点地图指南',
      summary: null,
      city: '杭州',
      destination: null,
      coverUrl: null,
      places: [
        { attractionId: '12', note: null },
        { attractionId: '13', note: '顺路可看飞来峰' }
      ]
    })
    // 契约外字段一律不上送。
    expect(payload).not.toHaveProperty('id')
    expect(payload).not.toHaveProperty('status')
    expect(payload).not.toHaveProperty('authorId')
    expect(payload).not.toHaveProperty('publishedAt')
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.emitted('saved')[0][0]).toMatchObject({ created: true })
  })

  it('无坐标或已停用的景点不会出现在地点候选项里', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    const options = wrapper.findAll('.place-select option').map((option) => option.text())
    expect(options.some((text) => text.includes('西湖'))).toBe(true)
    expect(options.some((text) => text.includes('灵隐寺'))).toBe(true)
    expect(options.some((text) => text.includes('无坐标景点'))).toBe(false)
    expect(options.some((text) => text.includes('已停用景点'))).toBe(false)
  })

  it('少于两个地点时不发请求，就地提示', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '指南标题').setValue('只有一个地点的指南')
    await field(wrapper, '所属城市').setValue('杭州')
    await wrapper.findAll('.place-select')[0].setValue('12')
    await flushPromises()

    await buttonByText(wrapper, '保存指南').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalled()
    expect(createPlaceGuide).not.toHaveBeenCalled()
    expect(updatePlaceGuide).not.toHaveBeenCalled()
  })

  it('编辑：回填地点并按界面顺序提交 PUT', async () => {
    updatePlaceGuide.mockResolvedValue({ id: '7', status: 'PUBLISHED' })
    const guide = {
      id: '7',
      title: '北京中轴线地图演示指南',
      summary: '演示摘要',
      city: '北京',
      destination: '北京',
      coverUrl: 'https://example.com/cover.jpg',
      status: 'PUBLISHED',
      places: [
        { attractionId: '12', name: '西湖', city: '杭州', longitude: 120.13, latitude: 30.24, note: '第一站', sortOrder: 1 },
        { attractionId: '13', name: '灵隐寺', city: '杭州', longitude: 120.1, latitude: 30.241, note: '第二站', sortOrder: 2 }
      ]
    }
    const wrapper = mountDialog(guide)
    await flushPromises()

    // 把第二行上移到首位后提交，顺序应以上移后的界面顺序为准。
    // 注意取第二行：第一行的「上移」是禁用的（已在首位），点它不会触发任何移动。
    const placeRows = wrapper.findAll('.place-row')
    await buttonByText(placeRows[1], '上移').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.place-note').map((input) => input.element.value)).toEqual(['第二站', '第一站'])
    await buttonByText(wrapper, '保存指南').trigger('click')
    await flushPromises()

    expect(updatePlaceGuide).toHaveBeenCalledTimes(1)
    const [guideId, payload] = updatePlaceGuide.mock.calls[0]
    expect(guideId).toBe('7')
    expect(payload.places.map((place) => place.attractionId)).toEqual(['13', '12'])
    expect(wrapper.emitted('saved')[0][0]).toMatchObject({ created: false })
  })

  it('候选景点取数失败时给出可重试的提示，重新打开会再取一次', async () => {
    fetchAttractions.mockRejectedValueOnce(new Error('网络异常'))
    const wrapper = mountDialog()
    await flushPromises()

    // 失败提示必须出现在表单里：候选为空时保存会被本地校验拦下，
    // 只说「地点不合法」会让运营以为是自己的选择有问题。
    expect(wrapper.find('.hint-error').text()).toContain('候选景点加载失败')
    expect(fetchAttractions).toHaveBeenCalledTimes(1)

    // 关掉再打开：失败不应被当成"已经取过"，否则整个会话都选不到景点。
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(fetchAttractions).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.hint-error').exists()).toBe(false)
    expect(wrapper.findAll('.place-select option').map((option) => option.text()).join())
      .toContain('西湖')
  })

  it('候选景点仍在加载时不提交：提示等待而不是把地点判为不合法', async () => {
    // 永不 resolve：模拟候选取数尚未返回时运营就点了保存。
    fetchAttractions.mockReturnValue(new Promise(() => {}))
    const guide = {
      id: '7',
      title: '杭州双景点地图指南',
      city: '杭州',
      destination: '杭州',
      places: [
        { attractionId: '12', name: '西湖', city: '杭州', longitude: 120.13, latitude: 30.24, note: '第一站', sortOrder: 1 },
        { attractionId: '13', name: '灵隐寺', city: '杭州', longitude: 120.1, latitude: 30.241, note: '第二站', sortOrder: 2 }
      ]
    }
    const wrapper = mountDialog(guide)
    await flushPromises()

    await buttonByText(wrapper, '保存指南').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith('正在加载候选景点，请稍候再保存')
    expect(updatePlaceGuide).not.toHaveBeenCalled()
  })

  it('首次加载成功后景点数据发生变化，再次打开会重新取候选（不命中旧缓存）', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    expect(fetchAttractions).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.place-select option').map((option) => option.text()).join())
      .not.toContain('新补坐标景点')

    // 另一位成员刚给景点补齐坐标（或新建了景点）：本次会话里它必须能选到。
    const justFixed = { id: '16', name: '新补坐标景点', city: '杭州', longitude: 120.2, latitude: 30.3, status: 'ACTIVE' }
    fetchAttractions.mockResolvedValue({ items: [westLake, lingyin, justFixed], totalPages: 1 })

    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(fetchAttractions).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.place-select option').map((option) => option.text()).join())
      .toContain('新补坐标景点')
  })

  it('候选刷新后，刚补齐坐标的景点不再被误判为「已停用或缺坐标」，保存可以正常提交', async () => {
    // 指南里已经引用了这条景点（另一位成员刚把它补齐坐标并加进指南）。
    const guide = {
      id: '7',
      title: '杭州双景点地图指南',
      city: '杭州',
      destination: '杭州',
      places: [
        { attractionId: '16', name: '新补坐标景点', city: '杭州', longitude: 120.2, latitude: 30.3, note: '第一站', sortOrder: 1 },
        { attractionId: '13', name: '灵隐寺', city: '杭州', longitude: 120.1, latitude: 30.241, note: '第二站', sortOrder: 2 }
      ]
    }
    // 本次取到的候选里还没有它（缓存了旧结果的实现会一直停在这个状态）。
    fetchAttractions.mockResolvedValue({ items: [lingyin], totalPages: 1 })
    const wrapper = mountDialog(guide)
    await flushPromises()

    expect(wrapper.findAll('.place-select option').map((option) => option.text()).join())
      .toContain('已停用或缺坐标')
    await buttonByText(wrapper, '保存指南').trigger('click')
    await flushPromises()
    expect(updatePlaceGuide).not.toHaveBeenCalled()

    // 重新打开：候选刷新后这条地点合法，保存必须放行（此前关掉重开仍然被拦）。
    const justFixed = { id: '16', name: '新补坐标景点', city: '杭州', longitude: 120.2, latitude: 30.3, status: 'ACTIVE' }
    fetchAttractions.mockResolvedValue({ items: [lingyin, justFixed], totalPages: 1 })
    updatePlaceGuide.mockResolvedValue({ id: '7', status: 'PUBLISHED' })
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.findAll('.place-select option').map((option) => option.text()).join())
      .not.toContain('已停用或缺坐标')
    await buttonByText(wrapper, '保存指南').trigger('click')
    await flushPromises()

    expect(updatePlaceGuide).toHaveBeenCalledTimes(1)
    expect(updatePlaceGuide.mock.calls[0][1].places.map((place) => place.attractionId))
      .toEqual(['16', '13'])
  })

  it('刷新候选失败时保留上一次的候选列表，不清空成不可用状态', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    expect(fetchAttractions).toHaveBeenCalledTimes(1)

    // 第二次打开时候选取数失败：仍要能用上一次取到的景点正常编辑，同时给出可重试提示。
    fetchAttractions.mockRejectedValue(new Error('网络异常'))
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.find('.hint-error').text()).toContain('候选景点加载失败')
    expect(wrapper.findAll('.place-select option').map((option) => option.text()).join())
      .toContain('西湖')
  })
})
