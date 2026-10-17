<script setup>
defineProps({ register: Boolean })
</script>

<template>
  <main class="travel-auth" :class="{ 'is-register': register }">
    <header class="auth-masthead">
      <RouterLink to="/" class="auth-wordmark">行迹</RouterLink>
      <RouterLink to="/" class="auth-back">返回探索 <span aria-hidden="true">↗</span></RouterLink>
    </header>

    <section class="auth-postcard" aria-label="行迹旅行账号">
      <aside class="auth-art">
        <div class="art-heading">
          <h2>把日常，<br>留在身后。</h2>
        </div>
        <svg class="landscape" viewBox="0 0 500 460" preserveAspectRatio="xMidYMax meet" fill="none" aria-hidden="true">
          <circle cx="354" cy="94" r="57" fill="#F7C775" />
          <g stroke="#F7FBFF" stroke-width="3" stroke-linecap="round">
            <path d="M64 87h68m-39-12h58M382 169h53m-28-12h52" />
            <path d="M58 143c23-20 42-13 60-1 17-15 31-14 43-6" />
          </g>
          <path d="M-20 278 104 132l74 91L286 104l181 180 53-46v222H-20Z" fill="#8BB5D2" />
          <path d="m239 157 47-53 70 70-46-11-27 10-22-21Z" fill="#F7FBFF" />
          <path d="m64 180 40-48 40 50-33-11-17 13-12-14Z" fill="#F7FBFF" />
          <path d="M-20 314 150 210l115 79 112-80 143 93v158H-20Z" fill="#4E8AB7" />
          <path d="M-20 336c102-35 161-2 237-17 109-22 162-18 303 26v115H-20Z" fill="#8BCEDA" />
          <g stroke="#E7F6FA" stroke-width="2" stroke-linecap="round">
            <path d="M192 345h109m28 20h65m-172 17h75m-224-7h57m216 24h67" />
          </g>
          <path d="M-20 336c56-29 87-11 142 23s124 57 218 55c90-2 118 0 180 26v20H-20Z" fill="#AACCC6" />
          <path d="M-20 357c105-18 91 59 234 72 54 5 118-1 181 31h-95c-92-15-116-3-170-25C65 409 75 384-20 386Z" fill="#EAF0E8" />
          <g fill="#346785">
            <path d="m31 268-24 55h15l-24 40h66l-24-40h15Z" />
            <path d="m461 270-29 64h18l-27 45h77l-29-45h17Z" />
            <path d="M28 341h6v48h-6zm430 11h6v47h-6z" />
          </g>
          <g transform="translate(145 334) rotate(9)">
            <path d="M0 15a12 12 0 0 1 12-12h73c12 0 19 7 23 17l12 28v22H0Z" fill="#F6CC7E" stroke="#36556D" stroke-width="3" />
            <path d="M68 15h17c5 0 8 3 10 8l9 21H68Z" fill="#E8F4FA" stroke="#36556D" stroke-width="3" />
            <rect x="12" y="15" width="42" height="29" rx="3" fill="#E8F4FA" stroke="#36556D" stroke-width="3" />
            <path d="M33 16v27M0 55h119M77 52h8" stroke="#36556D" stroke-width="3" />
            <circle cx="25" cy="70" r="12" fill="#36556D" /><circle cx="96" cy="70" r="12" fill="#36556D" />
            <circle cx="25" cy="70" r="5" fill="#F9FBFD" /><circle cx="96" cy="70" r="5" fill="#F9FBFD" />
            <rect x="19" y="-9" width="50" height="12" rx="3" fill="#DCA667" stroke="#36556D" stroke-width="3" />
            <path d="M29-9V2M59-9V2" stroke="#36556D" stroke-width="3" />
          </g>
        </svg>
      </aside>

      <div class="auth-content">
        <div class="auth-content-body"><slot /></div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.travel-auth {
  --auth-ink: var(--text-primary);
  --auth-muted: #6e7782;
  min-height: 100%;
  padding: 20px clamp(20px, 4vw, 64px) 16px;
  color: var(--auth-ink);
  background: #f5f7fa;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.auth-masthead { width: min(1080px, 100%); margin: 0 auto; display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.auth-masthead { padding-bottom: 16px; }
.auth-wordmark { font-size: 25px; font-weight: 850; letter-spacing: -1px; }
.auth-back { font-size: 12px; }
.auth-back span { margin-left: 8px; font-size: 20px; }
.auth-postcard { width: min(1080px, 100%); margin: 0 auto; display: grid; grid-template-columns: 42% 58%; background: var(--app-bg); border: 1px solid var(--border-divider); border-radius: 18px; overflow: hidden; }
.auth-art { position: relative; min-height: 540px; background: #e4f0f8; border-right: 1px solid #d7e5ef; overflow: hidden; display: flex; flex-direction: column; }
.art-heading { position: relative; z-index: 1; padding: 36px 34px 0; }
.art-heading h2 { font-size: 32px; line-height: 1.5; margin: 0; font-weight: 500; color: #36556d; }
.landscape { position: absolute; bottom: 0; display: block; width: 100%; height: auto; }
.auth-content { padding: 24px clamp(24px, 3.5vw, 54px) 18px; display: flex; flex-direction: column; min-width: 0; }
.auth-content-body { width: 100%; max-width: 360px; margin: auto; padding: 32px 0; }
.auth-content :deep(.auth-card-header) { margin-bottom: 28px; }
.auth-content :deep(h1) { font-size: 28px; font-weight: 650; margin-bottom: 10px; line-height: 1.35; }
.auth-content :deep(.form-field) { margin-bottom: 20px; gap: 8px; }
.auth-content :deep(.form-field label) { font-size: 12px; color: var(--auth-ink); font-weight: 600; }
.auth-content :deep(.form-field label span) { color: var(--auth-muted); font-weight: 400; }
.auth-content :deep(.form-field input) { width: 100%; min-height: 47px; border: 1px solid #d2d7df; border-radius: 7px; background: var(--app-bg); padding: 0 14px; color: var(--auth-ink); font-size: 13px; }
.auth-content :deep(.form-field input::placeholder) { color: #86868b; }
.auth-content :deep(.form-field input:focus) { border-color: var(--theme-blue); outline: 2px solid var(--theme-blue-tint); outline-offset: 2px; }
.auth-content :deep(.auth-submit) { width: 100%; min-height: 50px; display: flex; align-items: stretch; justify-content: space-between; padding: 0; color: #ffffff; background: var(--theme-blue); border: 1px solid transparent; border-radius: 8px; font-size: 14px; font-weight: 700; cursor: pointer; transition: background .15s; margin: 8px 0 0; }
.auth-content :deep(.auth-submit:hover:not(:disabled)) { background: var(--theme-blue-hover); }
.auth-content :deep(.auth-submit:active:not(:disabled)) { background: var(--theme-blue); }
.auth-content :deep(.auth-submit:disabled) { opacity: .6; cursor: wait; }
.auth-content :deep(.auth-submit > span:first-child) { display: flex; align-items: center; padding: 12px 20px; }
.auth-content :deep(.auth-submit-arrow) { display: grid; place-items: center; flex: 0 0 48px; border-left: 1px dashed rgba(255, 255, 255, .45); }
.auth-content :deep(.auth-submit-arrow span) { display: inline-block; font-size: 22px; line-height: 1; transition: transform .18s ease; }
.auth-content :deep(.auth-submit:hover:not(:disabled) .auth-submit-arrow span) { transform: translate(2px, -2px); }
.auth-content :deep(.auth-card-footer) { margin-top: 24px; display: flex; flex-wrap: wrap; gap: 6px; font-size: 12px; color: var(--auth-muted); }
.auth-content :deep(.text-link) { color: var(--theme-blue); text-decoration: underline; text-underline-offset: 4px; font-weight: 600; }
.auth-content :deep(.form-error) { color: #9b3024; background: #fae6dc; border-radius: 6px; padding: 10px 12px; margin-bottom: 16px; font-size: 12px; }
.auth-content a:focus-visible, .auth-masthead a:focus-visible, .auth-content :deep(button:focus-visible) { outline: 2px solid var(--theme-blue); outline-offset: 4px; }
.is-register .auth-content-body { padding: 22px 0; }
.is-register .auth-content :deep(.auth-card-header) { margin-bottom: 20px; }
.is-register .auth-content :deep(.form-field) { margin-bottom: 13px; gap: 6px; }
.is-register .auth-content :deep(.form-field input) { min-height: 42px; }
.is-register .auth-content :deep(.auth-card-footer) { margin-top: 18px; }
@media (min-width: 901px) and (max-width: 1120px) {
  .art-heading { padding: 30px 24px 0; }
  .auth-content { padding-right: 24px; padding-left: 24px; }
}
@media (max-width: 680px) {
  .travel-auth { padding: 20px 18px; }
  .auth-masthead { padding-bottom: 18px; }
  .auth-wordmark { font-size: 22px; }
  .auth-postcard { grid-template-columns: 1fr; border-radius: 12px; }
  .auth-art { min-height: 180px; height: 180px; border-right: 0; border-bottom: 1px solid #d7e5ef; }
  .art-heading { padding: 22px; }
  .art-heading h2 { font-size: 23px; margin: 0; }
  .art-heading p, .art-caption { display: none; }
  .landscape { position: absolute; width: 50%; height: 100%; right: 0; bottom: 0; margin: 0; }
  .auth-content { padding: 20px 24px; }
  .auth-content-body { padding: 24px 0; }
  .auth-content :deep(h1) { font-size: 27px; }
  .auth-content :deep(.auth-card-header) { margin-bottom: 24px; }
}
@media (prefers-reduced-motion: reduce) {
  .auth-content :deep(.auth-submit), .auth-content :deep(.auth-submit-arrow span) { transition: none; }
  .auth-content :deep(.auth-submit:hover:not(:disabled) .auth-submit-arrow span) { transform: none; }
}
</style>
