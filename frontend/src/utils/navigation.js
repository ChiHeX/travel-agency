export function returnToPrevious(router, fallback) {
  if (window.history.state?.back) {
    router.back()
  } else {
    router.replace(fallback)
  }
}
