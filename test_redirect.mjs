async function test() {
  try {
    // 1. Signup
    const email = `user_${Date.now()}@example.com`;
    const signupRes = await fetch('http://localhost:8080/api/auth/signup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Test User', email, password: 'password123' })
    });
    const signupData = await signupRes.json();
    console.log('Signup:', signupData);

    const token = signupData.token;

    // 2. Shorten URL with "google.com" (without https://)
    const shortenRes1 = await fetch('http://localhost:8080/api/shorten', {
      method: 'POST',
      headers: { 
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ url: 'google.com' })
    });
    const shortenData1 = await shortenRes1.json();
    console.log('Shorten google.com:', shortenData1);

    // 3. Shorten URL with "https://github.com"
    const shortenRes2 = await fetch('http://localhost:8080/api/shorten', {
      method: 'POST',
      headers: { 
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ url: 'https://github.com' })
    });
    const shortenData2 = await shortenRes2.json();
    console.log('Shorten https://github.com:', shortenData2);

    // 4. Test GET redirect on short code for google.com
    const redirectRes1 = await fetch(`http://localhost:8080/${shortenData1.shortCode}`, {
      redirect: 'manual'
    });
    console.log(`Redirect status for google.com: ${redirectRes1.status}, Location header: ${redirectRes1.headers.get('location')}`);

    // 5. Test GET redirect on short code for https://github.com
    const redirectRes2 = await fetch(`http://localhost:8080/${shortenData2.shortCode}`, {
      redirect: 'manual'
    });
    console.log(`Redirect status for https://github.com: ${redirectRes2.status}, Location header: ${redirectRes2.headers.get('location')}`);

  } catch (err) {
    console.error('Error during test:', err);
  }
}

test();
