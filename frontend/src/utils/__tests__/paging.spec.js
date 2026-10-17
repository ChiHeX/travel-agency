import { describe, expect, it, vi } from 'vitest'
import { CONTRACT_MAX_PAGE_SIZE, MAX_PAGES, fetchAllPages } from '../paging'

/**
 * 候选项分页取数工具的测试。
 *
 * <p>背景：线路行程表单里的酒店 / 景点下拉此前只取第一页（size=100），
 * 第 101 条之后的资源在界面上永远选不到。这里钉住"按契约页大小翻完所有页"，
 * 以及 totalPages 异常时不会无限翻页。</p>
 */
describe('fetchAllPages', () => {
  it('按契约页大小逐页取完 totalPages，第 101 条之后的候选项不会被丢掉', async () => {
    const fetchPage = vi.fn(async ({ page, size }) => ({
      items: Array.from({ length: size }, (_, index) => `候选-${(page - 1) * size + index + 1}`),
      totalPages: 3
    }))

    const items = await fetchAllPages(fetchPage)

    expect(fetchPage).toHaveBeenNthCalledWith(1, { page: 1, size: CONTRACT_MAX_PAGE_SIZE })
    expect(fetchPage).toHaveBeenNthCalledWith(2, { page: 2, size: CONTRACT_MAX_PAGE_SIZE })
    expect(fetchPage).toHaveBeenNthCalledWith(3, { page: 3, size: CONTRACT_MAX_PAGE_SIZE })
    expect(fetchPage).toHaveBeenCalledTimes(3)
    expect(items).toHaveLength(300)
    expect(items[299]).toBe('候选-300')
  })

  it('只有一页时只发一次请求，不改动分页契约参数', async () => {
    const fetchPage = vi.fn().mockResolvedValue({ items: [{ id: '1' }], totalPages: 1 })

    const items = await fetchAllPages(fetchPage)

    expect(fetchPage).toHaveBeenCalledTimes(1)
    expect(items).toEqual([{ id: '1' }])
  })

  it('首页为空时返回空数组，不把 items 变成 null', async () => {
    const fetchPage = vi.fn().mockResolvedValue({ items: [], totalPages: 0 })

    await expect(fetchAllPages(fetchPage)).resolves.toEqual([])
    expect(fetchPage).toHaveBeenCalledTimes(1)
  })

  it('后端漏返回 items 时按空处理，不抛异常', async () => {
    const fetchPage = vi.fn().mockResolvedValue({ totalPages: 1 })

    await expect(fetchAllPages(fetchPage)).resolves.toEqual([])
  })

  it('totalPages 非法时不继续翻页，且始终受 maxPages 上限保护', async () => {
    const broken = vi.fn().mockResolvedValue({ items: [{ id: '1' }], totalPages: 'unknown' })
    await expect(fetchAllPages(broken)).resolves.toEqual([{ id: '1' }])
    expect(broken).toHaveBeenCalledTimes(1)

    // 异常大的 totalPages 不能变成无上限的连续请求。
    const huge = vi.fn(async () => ({ items: [], totalPages: 100000 }))
    await fetchAllPages(huge)
    expect(huge).toHaveBeenCalledTimes(MAX_PAGES)
  })
})
