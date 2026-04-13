const { onCall, HttpsError } = require("firebase-functions/v2/https");

// Set the region to London (europe-west2) inside the onCall options
exports.getArborStudents = onCall(
    { region: "europe-west2" },
    async (request) => {
        // 1. Set your Arbor Sandbox credentials
        const appUsername = "deven@techandthat.com"; // [cite: 8]
        const appPassword = "YOUR_APP_PASSWORD"; // [cite: 7]

        // 2. Encode credentials for Basic Auth
        const credentials = Buffer.from(`${appUsername}:${appPassword}`).toString('base64');

        // 3. Set the Sandbox URL and append ?format=json [cite: 299, 196]
        const arborUrl = "https://api-sandbox2.uk.arbor.sc/rest-v2/students?format=json";

        try {
            // 4. Make the GET request to Arbor
            const response = await fetch(arborUrl, {
                method: 'GET',
                headers: {
                    'Authorization': `Basic ${credentials}`,
                    'Accept': 'application/json'
                }
            });

            if (!response.ok) {
                throw new Error(`Arbor API error: ${response.status}`);
            }

            // 5. Parse the JSON and return it to your Android app
            const jsonResponse = await response.json();
            return jsonResponse;

        } catch (error) {
            console.error("Error fetching data from Arbor:", error);
            throw new HttpsError('internal', 'Unable to connect to Arbor API');
        }
    }
);