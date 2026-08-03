// Automates the Openfire first-run setup wizard + creates the 3 XMPP
// accounts the auction-sniper tests need, so `docker volume rm` + rerun
// doesn't require clicking through the wizard by hand every time.
//
// Selectors below were taken directly from the Openfire source
// (igniterealtime/Openfire, xmppserver/src/main/webapp/setup/*.jsp and
// user-create.jsp on the `main` branch), not guessed from screenshots.
//
// Usage:
//   npm install playwright   (once)
//   node docker/setup-openfire.js
//
// Assumes `docker compose up -d openfire` has already been run and
// http://localhost:9090 is reachable.

const { chromium } = require('playwright');

const BASE = 'http://localhost:9090';
const ADMIN_EMAIL = 'admin@localhost';
const ADMIN_PASSWORD = 'adminpass';

const ACCOUNTS = [
  { username: 'sniper', password: 'sniper' },
  { username: 'auction-item-54321', password: 'auction' },
  { username: 'auction-item-65432', password: 'auction' },
];

// Right after setup finishes, Openfire's web layer is briefly flaky -- some
// internal modules (e.g. UpdateManager) aren't fully initialized yet, which
// can surface as a transient 500 (NullPointerException) or even
// ERR_CONNECTION_RESET for a few seconds. Retry through that window instead
// of failing outright.
async function gotoRobust(page, url, { retries = 8, delayMs = 3000 } = {}) {
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      await page.goto(url, { waitUntil: 'load', timeout: 15000 });
      const text = await page.textContent('body').catch(() => '');
      if (/HTTP ERROR 500|NullPointerException/.test(text)) {
        throw new Error('transient 500 from Openfire');
      }
      return;
    } catch (err) {
      if (attempt === retries) throw err;
      console.log(`  (transient error loading ${url}, retrying in ${delayMs}ms: ${err.message})`);
      await page.waitForTimeout(delayMs);
    }
  }
}

async function clickContinue(page) {
  await Promise.all([
    page.waitForLoadState('networkidle'),
    page.click('#jive-setup-save'),
  ]);
}

async function runSetupWizard(page) {
  console.log('== setup: language ==');
  await page.goto(`${BASE}/setup/index.jsp`);

  if (page.url().includes('setup-completed.jsp')) {
    console.log('Openfire setup was already completed, skipping wizard.');
    return false;
  }

  await page.check('#en');
  await clickContinue(page);

  console.log('== setup: server settings ==');
  await page.fill('#domain', 'localhost');
  await page.fill('#fqdn', 'localhost');
  await page.uncheck('input[name="restrictAdminLocalhost"]');
  await clickContinue(page);

  console.log('== setup: database (embedded) ==');
  await page.check('#rb01'); // mode=embedded
  await clickContinue(page);

  console.log('== setup: profile (default) ==');
  await page.check('#rb01'); // mode=default
  await clickContinue(page);

  console.log('== setup: admin account ==');
  await page.fill('#email', ADMIN_EMAIL);
  await page.fill('#newPassword', ADMIN_PASSWORD);
  await page.fill('#newPasswordConfirm', ADMIN_PASSWORD);
  await clickContinue(page);

  console.log('== setup: finished, waiting for Openfireto run stably, 10 seconds ==');
  await page.waitForLoadState('networkidle');
  // Modules (e.g. UpdateManager, the user provider) finish initializing a
  // few seconds after setup-finished.jsp is reached. Give it real time
  // before touching anything auth-related, rather than relying on retries
  // to paper over an admin account that isn't fully committed yet.
  await page.waitForTimeout(10000);
  return true;
}

async function login(page) {
  console.log('== logging in to admin console ==');
  const maxAttempts = 6;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    await gotoRobust(page, `${BASE}/login.jsp`);
    await page.fill('#u01', 'admin');
    await page.fill('#p01', ADMIN_PASSWORD);
    await page.click('#submit');
    await page.waitForTimeout(1500);

    // The post-login redirect target (index.jsp, the dashboard) can itself
    // 500 transiently -- that's fine, we only need the session cookie from
    // the login POST, not the dashboard to render. Bounce off it with retry.
    await gotoRobust(page, `${BASE}/user-create.jsp`);
    if (await page.locator('#u01').count() === 0) {
      return; // logged in
    }

    if (attempt === maxAttempts) {
      throw new Error('Login to Openfire admin console failed (still on login form) after retries.');
    }
    console.log(`  (login did not stick, retrying in 4000ms, attempt ${attempt + 1}/${maxAttempts})`);
    await page.waitForTimeout(4000);
  }
}

// The admin session sometimes drops between the fast, scripted navigations
// (old JSP app, no SPA state) -- bouncing back to login.jsp. Detect that and
// log back in rather than failing the whole run.
async function ensureLoggedIn(page) {
  if (await page.locator('#u01').count() > 0) {
    console.log('  (session dropped, logging back in)');
    await login(page);
  }
}

async function createAccounts(page) {
  for (const { username, password } of ACCOUNTS) {
    console.log(`== creating user: ${username} ==`);
    await gotoRobust(page, `${BASE}/user-create.jsp`);
    await ensureLoggedIn(page);

    await page.fill('#usernametf', username);
    await page.fill('input[name="password"]', password);
    await page.fill('input[name="passwordConfirm"]', password);
    await Promise.all([
      page.waitForLoadState('networkidle'),
      page.click('input[name="create"]'),
    ]);

    const bodyText = await page.textContent('body');
    if (/already exists/i.test(bodyText)) {
      console.log(`  (already existed, skipped)`);
    }
    await page.waitForTimeout(500);
  }
}

(async () => {
  // headless: false so you can watch it work the first time; the wizard's
  // exact flow wasn't verified against a live instance beforehand, so if a
  // step fails you'll see where and a screenshot is saved for debugging.
  const browser = await chromium.launch({ headless: false, slowMo: 100 });
  const page = await browser.newPage();

  try {
    const didSetup = await runSetupWizard(page);
    await login(page);
    await createAccounts(page);
    console.log(didSetup
      ? 'Done: Openfire configured and test accounts created.'
      : 'Done: test accounts (re)created against existing Openfire setup.');
  } catch (err) {
    const shot = require('path').join(__dirname, 'setup-openfire-error.png');
    await page.screenshot({ path: shot });
    console.error(`Failed at ${page.url()} -- screenshot saved to ${shot}`);
    throw err;
  } finally {
    await browser.close();
  }
})();
