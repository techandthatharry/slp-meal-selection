// Implements callable endpoints for Arbor sync and Firestore updates.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

// Ensure Firebase Admin is initialized once per runtime.
if (admin.apps.length === 0) {
  admin.initializeApp();
}

// Shared Firestore reference for function writes.
const db = admin.firestore();

/**
 * Pauses execution for a given number of milliseconds.
 *
 * @param {number} ms Milliseconds to sleep.
 * @return {Promise<void>} Resolves after the delay.
 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Returns the first non-empty value.
 *
 * @param {...*} values Candidate values.
 * @return {*} First defined, non-empty value.
 */
function firstDefined(...values) {
  return values.find((value) => {
    // Treat empty strings as undefined values.
    if (typeof value === "string") return value.trim().length > 0;
    return value !== undefined && value !== null;
  });
}

/**
 * Slugifies arbitrary text for safe Firestore doc IDs.
 *
 * @param {*} value Raw value.
 * @return {string} Slug value.
 */
function toSlug(value) {
  return String(value == null ? "" : value)
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");
}

/**
 * Extract object values as row objects.
 *
 * @param {*} value Candidate object.
 * @return {Array<Object>} Extracted row objects.
 */
function objectValuesAsRows(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return [];

  // Keep only object-like rows when converting map payloads.
  return Object.values(value)
      .filter((item) => item && typeof item === "object");
}

/**
 * Extracts the student list from possible Arbor response shapes.
 *
 * @param {*} payload Arbor JSON payload.
 * @return {Array<Object>} Student rows.
 */
function extractStudents(payload) {
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== "object") return [];

  // Handle common Arbor array containers.
  if (Array.isArray(payload.data)) return payload.data;
  if (payload.data && Array.isArray(payload.data.students)) {
    return payload.data.students;
  }

  if (Array.isArray(payload.results)) return payload.results;
  if (Array.isArray(payload.students)) return payload.students;

  // Handle nested data payload under students key.
  if (payload.students && payload.students.data &&
      Array.isArray(payload.students.data)) {
    return payload.students.data;
  }

  // Handle object-map style students payload.
  const studentObjectRows = objectValuesAsRows(payload.students);
  if (studentObjectRows.length > 0) return studentObjectRows;

  // Handle wrapper payloads with response.students.
  if (payload.response && Array.isArray(payload.response.students)) {
    return payload.response.students;
  }

  // Handle single-student object payload.
  if (payload.student && typeof payload.student === "object") {
    return [payload.student];
  }

  // Last attempt: treat data object values as rows.
  if (payload.data && typeof payload.data === "object") {
    return objectValuesAsRows(payload.data);
  }

  return [];
}

/**
 * Extracts dietary requirements from an Arbor student payload.
 *
 * @param {Object} rawStudent Raw Arbor student object.
 * @return {Array<string>} Dietary requirement labels.
 */
function extractDietaryRequirements(rawStudent) {
  const value =
    firstDefined(
        rawStudent && rawStudent.dietary_requirements,
        rawStudent && rawStudent.dietaryRequirements,
        rawStudent && rawStudent.dietary_needs,
        rawStudent && rawStudent.dietaryNeeds,
        rawStudent && rawStudent.allergies,
    );

  // Normalize array payloads into readable dietary labels.
  if (Array.isArray(value)) {
    return value
        .map((item) => {
          if (typeof item === "string") return item.trim();
          if (item && typeof item === "object") {
            return firstDefined(item.name, item.label, item.description);
          }
          return null;
        })
        .filter((item) => !!item);
  }

  // Normalize single-string payloads into a one-item array.
  if (typeof value === "string" && value.trim().length > 0) {
    return [value.trim()];
  }

  return [];
}

/**
 * Returns a fallback meal option for demo data completeness.
 *
 * @param {number} index Student index.
 * @return {string} Meal label.
 */
function fallbackMealSelection(index) {
  const meals = [
    "Tomato Pasta",
    "Fish & Chips",
    "Jacket Potato",
    "Vegetable Curry",
    "Chicken Wrap",
  ];
  return meals[index % meals.length];
}

/**
 * Returns fallback dietary requirements for demo data completeness.
 *
 * @param {number} index Student index.
 * @return {Array<string>} Dietary requirement labels.
 */
function fallbackDietaryRequirements(index) {
  const requirements = [
    ["No known dietary requirements"],
    ["Nut allergy"],
    ["Vegetarian"],
    ["Dairy-free"],
    ["Gluten-free"],
  ];
  return requirements[index % requirements.length];
}

/**
 * Maps one Arbor student object to app Firestore record shape.
 *
 * @param {Object} rawStudent Raw Arbor student object.
 * @param {number} index Student index for fallback values.
 * @return {{childName: string, className: string, mealSelected: string,
 * dietaryRequirements: Array<string>}|null} Mapped record.
 */
function mapArborStudent(rawStudent, index) {
  const person = rawStudent && rawStudent.person;
  const firstName = firstDefined(
      rawStudent && rawStudent.first_name,
      rawStudent && rawStudent.firstName,
      person && person.preferredFirstName,
      person && person.legalFirstName,
  );
  const lastName = firstDefined(
      rawStudent && rawStudent.last_name,
      rawStudent && rawStudent.lastName,
      person && person.preferredLastName,
      person && person.legalLastName,
  );
  const fullName = firstDefined(
      rawStudent && rawStudent.full_name,
      rawStudent && rawStudent.name,
      rawStudent && rawStudent.student_name,
      person && person.fullName,
      [firstName, lastName].filter(Boolean).join(" ").trim(),
  );

  const className = firstDefined(
      rawStudent && rawStudent.registration_form,
      rawStudent && rawStudent.form,
      rawStudent && rawStudent.class_name,
      rawStudent && rawStudent.className,
      rawStudent && rawStudent.year_group_name,
      rawStudent && rawStudent.year_group,
      rawStudent && rawStudent.registrationForm,
      "Unknown",
  );

  // Drop rows that do not have a usable display name.
  if (!fullName) return null;

  // Resolve meal choice using Arbor fields first, then fallback value.
  const mealSelected = firstDefined(
      rawStudent && rawStudent.meal_selected,
      rawStudent && rawStudent.mealSelected,
      rawStudent && rawStudent.meal_choice,
      rawStudent && rawStudent.mealChoice,
      fallbackMealSelection(index),
  );

  // Resolve dietary requirements and fallback to demo values when absent.
  const dietaryRequirements = extractDietaryRequirements(rawStudent);
  const finalDietaryRequirements = dietaryRequirements.length > 0 ?
    dietaryRequirements :
    fallbackDietaryRequirements(index);

  return {
    arborStudentId: rawStudent && rawStudent.id ? String(rawStudent.id) : null,
    childName: String(fullName).trim(),
    className: String(className).trim(),
    mealSelected: String(mealSelected).trim(),
    dietaryRequirements: finalDietaryRequirements,
  };
}

/**
 * Gets Arbor API credentials and throws when missing.
 *
 * @return {{username: string, password: string}} Arbor credentials.
 */
function getArborCredentials() {
  const username = process.env.ARBOR_APP_USERNAME || "deven@techandthat.com";
  const password = process.env.ARBOR_APP_PASSWORD || "V3DbrM!m3swruY!";

  if (!username || !password || password === "YOUR_APP_PASSWORD") {
    throw new HttpsError(
        "failed-precondition",
        "Arbor credentials are not configured. " +
        "Set ARBOR_APP_USERNAME and ARBOR_APP_PASSWORD.",
    );
  }

  return {username, password};
}

/**
 * Builds a Basic authorization header for Arbor requests.
 *
 * @param {string} username Arbor app username.
 * @param {string} password Arbor app password.
 * @return {{Authorization: string, Accept: string}} Headers.
 */
function buildArborAuthHeaders(username, password) {
  const authString = `${username}:${password}`;
  const credentials = Buffer.from(authString).toString("base64");
  return {
    "Authorization": `Basic ${credentials}`,
    "Accept": "application/json",
    // Required: Arbor's WAF blocks requests that lack a browser User-Agent.
    "User-Agent":
      "Mozilla/5.0 (compatible; SLPMealSync/1.0; +https://techandthat.com)",
  };
}

/**
 * Sends one billing queue row to Arbor sandbox using a generic endpoint.
 *
 * @param {Object} queueItem Billing queue payload from Firestore.
 * @param {string} docId Queue document id for traceability.
 * @param {Object} headers Arbor auth headers.
 * @return {Promise<{ok: boolean, statusCode: number, body: *,
 * textBody: string}>}
 * HTTP result.
 */
async function postBillingItemToArbor(queueItem, docId, headers) {
  const url = "https://api-sandbox2.uk.arbor.sc/rest-v2/meal-provisions?format=json";
  const mealProvisionPayload = {
    sourceDocumentId: docId,
    schoolName: queueItem.schoolName || "",
    className: queueItem.className || "",
    meal: queueItem.meal || "",
    createdAt: queueItem.createdAt || "",
  };
  const parseResponse = async (response) => {
    const rawText = await response.text();

    let body = null;
    try {
      body = rawText ? JSON.parse(rawText) : null;
    } catch (error) {
      body = null;
    }

    return {
      ok: response.ok,
      statusCode: response.status,
      body,
      textBody: rawText,
    };
  };

  const payloadVariants = [
    {
      variantName: "MealProvision",
      envelope: {request: {MealProvision: mealProvisionPayload}},
    },
    {
      variantName: "MealProvisionArray",
      envelope: {request: {MealProvision: [mealProvisionPayload]}},
    },
    {
      variantName: "mealProvision",
      envelope: {request: {mealProvision: mealProvisionPayload}},
    },
    {
      variantName: "mealProvisionArray",
      envelope: {request: {mealProvision: [mealProvisionPayload]}},
    },
    {
      variantName: "meal_provision",
      envelope: {request: {meal_provision: mealProvisionPayload}},
    },
    {
      variantName: "meal_provision_array",
      envelope: {request: {meal_provision: [mealProvisionPayload]}},
    },
    {
      variantName: "mealProvision_post",
      envelope: {request: {mealProvision_post: mealProvisionPayload}},
    },
  ];

  const attempts = [];
  payloadVariants.forEach((variant) => {
    const requestJson = JSON.stringify(variant.envelope);

    const params = new URLSearchParams();
    params.append("request", requestJson);

    attempts.push({
      name: `form_request_param:${variant.variantName}`,
      headers: {
        ...headers,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: params.toString(),
    });

    const wrappedRequestParamJson = JSON.stringify({request: requestJson});
    attempts.push({
      name: `json_request_string_param:${variant.variantName}`,
      headers: {
        ...headers,
        "Content-Type": "application/json",
      },
      body: wrappedRequestParamJson,
    });

    attempts.push({
      name: `json_object_body:${variant.variantName}`,
      headers: {
        ...headers,
        "Content-Type": "application/json",
      },
      body: requestJson,
    });
  });

  const attemptResults = [];
  let lastResponse = null;
  for (const attempt of attempts) {
    const response = await fetch(url, {
      method: "POST",
      headers: attempt.headers,
      body: attempt.body,
    });

    const parsed = await parseResponse(response);
    attemptResults.push({
      attempt: attempt.name,
      statusCode: parsed.statusCode,
      ok: parsed.ok,
      body: parsed.body,
      textBody: parsed.textBody,
    });

    if (parsed.ok) {
      return {
        ...parsed,
        attemptResults,
        attemptName: attempt.name,
      };
    }

    lastResponse = {
      ...parsed,
      attemptResults,
      attemptName: attempt.name,
      textBody: `[${attempt.name}] ${parsed.textBody || ""}`,
    };
  }

  return lastResponse || {
    ok: false,
    statusCode: 500,
    body: null,
    textBody: "No Arbor upload attempt was executed",
  };
}

// Callable endpoint to pre-build a studentId → className map from Arbor.
// Fetches all current-year registration forms and their memberships,
// then stores className for each student in arborClassMap/{studentId}.
exports.buildArborClassMap = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
      // 360s handles ~15 real-school forms + ~450 membership details.
      timeoutSeconds: 360,
    },
    async (request) => {
      const schoolName = (
        request.data &&
        typeof request.data.schoolName === "string" &&
        request.data.schoolName.trim()
      ) || "Arbor Sandbox";
      const formOffset = Number(request.data && request.data.formOffset) || 0;
      const formBatchSize = 20; // fetch this many forms per callable invocation

      const {username, password} = getArborCredentials();
      const authHeaders = buildArborAuthHeaders(username, password);
      const DELAY_MS = 750;

      try {
        // Determine current academic year by today's date.
        const yearListResp = await fetch(
            "https://api-sandbox2.uk.arbor.sc/rest-v2/academic-years" +
            "?format=json",
            {method: "GET", headers: authHeaders},
        );
        if (!yearListResp.ok) {
          return {
            success: false,
            message: "Failed to fetch academic years: HTTP " +
              yearListResp.status,
          };
        }
        await sleep(DELAY_MS);
        const yearJson = await yearListResp.json();
        const allYears = (yearJson && yearJson.academicYears) || [];

        // Pick the academic year whose range contains today.
        const today = new Date();
        let currentYearHref = null;
        for (const yr of allYears) {
          if (!yr.href) continue;
          // Fetch year detail to check start/end dates (list has hrefs only).
          const yrResp = await fetch(
              `https://api-sandbox2.uk.arbor.sc${yr.href}?format=json`,
              {method: "GET", headers: authHeaders},
          );
          await sleep(DELAY_MS);
          if (!yrResp.ok) continue;
          const yrJson = await yrResp.json();
          const yrData = yrJson && yrJson.academicYear;
          if (!yrData) continue;
          const start = yrData.startDate ? new Date(yrData.startDate) : null;
          const end = yrData.endDate ? new Date(yrData.endDate) : null;
          if (start && end && today >= start && today <= end) {
            currentYearHref = yr.href;
            break;
          }
        }

        if (!currentYearHref) {
          return {
            success: false,
            message: "Could not determine current academic year.",
          };
        }

        // Fetch all registration forms for the current academic year.
        const formsResp = await fetch(
            "https://api-sandbox2.uk.arbor.sc/rest-v2/registration-forms" +
            `?academicYear=${encodeURIComponent(currentYearHref)}&format=json`,
            {method: "GET", headers: authHeaders},
        );
        await sleep(DELAY_MS);
        if (!formsResp.ok) {
          return {
            success: false,
            message: "Failed to fetch registration forms: HTTP " +
              formsResp.status,
          };
        }
        const formsJson = await formsResp.json();
        const allFormHrefs = (formsJson && formsJson.registrationForms) || [];
        const totalForms = allFormHrefs.length;

        // Paginate through forms in batches.
        const formBatchEnd = Math.min(formOffset + formBatchSize, totalForms);
        const formsBatch = allFormHrefs.slice(formOffset, formBatchEnd);

        let entriesWritten = 0;
        const firestoreBatch = db.batch();

        for (const formRef of formsBatch) {
          await sleep(DELAY_MS);
          try {
            const formDetailResp = await fetch(
                `https://api-sandbox2.uk.arbor.sc${formRef.href}?format=json`,
                {method: "GET", headers: authHeaders},
            );
            if (!formDetailResp.ok) continue;
            const formDetail = await formDetailResp.json();
            const form = formDetail && formDetail.registrationForm;
            if (!form) continue;

            const className =
              form.shortName ||
              form.registrationFormName ||
              "Unknown";
            const memberships = form.studentMemberships || [];

            for (const membership of memberships) {
              await sleep(DELAY_MS);
              try {
                const memResp = await fetch(
                    `https://api-sandbox2.uk.arbor.sc${membership.href}` +
                    "?format=json",
                    {method: "GET", headers: authHeaders},
                );
                if (!memResp.ok) continue;
                const memJson = await memResp.json();
                const memData = memJson && memJson.registrationFormMembership;
                const student = memData && memData.student;
                if (!student || !student.id) continue;

                // Store className keyed by Arbor student ID (no personal data).
                const docRef = db.collection("arborClassMap")
                    .doc(String(student.id));
                firestoreBatch.set(docRef, {
                  className,
                  academicYearHref: currentYearHref,
                  updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                }, {merge: true});
                entriesWritten++;
              } catch (memErr) {
                logger.warn("Failed to fetch membership detail", {
                  href: membership.href,
                  error: memErr && memErr.message,
                });
              }
            }
          } catch (formErr) {
            logger.warn("Failed to fetch form detail", {
              href: formRef.href,
              error: formErr && formErr.message,
            });
          }
        }

        if (entriesWritten > 0) await firestoreBatch.commit();

        const nextFormOffset = formBatchEnd;
        const hasMore = nextFormOffset < totalForms;

        logger.info("Arbor class map batch complete", {
          schoolName,
          formOffset,
          formBatchEnd,
          totalForms,
          entriesWritten,
          hasMore,
        });

        return {
          success: true,
          schoolName,
          formOffset,
          nextFormOffset,
          totalForms,
          entriesWritten,
          hasMore,
          message: hasMore ?
            `Class map batch: ${nextFormOffset}/${totalForms} forms done.` :
            `Class map built. ${entriesWritten} entries written.`,
        };
      } catch (error) {
        logger.error("buildArborClassMap error", {
          message: error && error.message ? error.message : "unknown",
        });
        return {
          success: false,
          message: "Class map build failed.",
          errorMessage: error && error.message ? error.message : "unknown",
        };
      }
    },
);

// Callable endpoint to pull Arbor students and upsert them into Firestore.
exports.getArborStudents = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
      // 90s covers 100 students × 200ms + Retry-After overhead.
      timeoutSeconds: 90,
    },
    async (request) => {
      const schoolName = (
        request.data &&
        typeof request.data.schoolName === "string" &&
        request.data.schoolName.trim()
      ) || "Arbor Sandbox";
      const requestedMaxRecords = Number(
          request.data && request.data.maxRecords,
      );
      // Cap at 100: 100 × 200ms ≈ 20s, within the 90s client timeout.
      const maxRecords =
        Number.isFinite(requestedMaxRecords) && requestedMaxRecords > 0 ?
          Math.min(Math.floor(requestedMaxRecords), 100) :
          100;
      const requestedOffset = Number(
          request.data && request.data.offset,
      );
      const offset =
        Number.isFinite(requestedOffset) && requestedOffset >= 0 ?
          Math.floor(requestedOffset) :
          0;

      const {username, password} = getArborCredentials();
      const authHeaders = buildArborAuthHeaders(username, password);
      const arborUrl = "https://api-sandbox2.uk.arbor.sc/rest-v2/students?format=json";

      try {
        // Fetch student list from Arbor list endpoint.
        const response = await fetch(arborUrl, {
          method: "GET",
          headers: authHeaders,
        });

        // Return friendly payload for HTTP errors and rate limits.
        if (!response.ok) {
          const rateLimited = response.status === 429;
          logger.warn("Arbor API request failed", {
            status: response.status,
            rateLimited,
          });
          return {
            success: false,
            rateLimited,
            fetched: 0,
            written: 0,
            schoolName,
            offset,
            hasMore: false,
            nextOffset: offset,
            totalAvailable: 0,
            statusCode: response.status,
            message: rateLimited ?
              "Arbor API rate limit reached. Please retry in a minute." :
              `Arbor API returned HTTP ${response.status}`,
          };
        }

        const jsonResponse = await response.json();
        const rawStudents = extractStudents(jsonResponse);

        const totalAvailable = rawStudents.length;
        const pageStart = Math.min(offset, totalAvailable);
        const pageEnd = Math.min(pageStart + maxRecords, totalAvailable);
        const pageRows = rawStudents.slice(pageStart, pageEnd);

        const detailedStudents = [];
        let rateLimitedDuringDetails = false;
        let rowsAttempted = pageRows.length;

        // Fetch each student sequentially with a 200ms delay.
        // 200ms = ~300 req/min.
        const INTER_REQUEST_DELAY_MS = 200;
        // On 429 in the callable we stop immediately (MAX_RETRIES=0) and
        // return the current position so Android can wait then resume.
        // This avoids blowing the 90s callable timeout with Retry-After sleeps.
        const MAX_RETRIES_ON_429 = 0;
        // Unused in callable (no retries); kept to document the value.
        const DEFAULT_RETRY_AFTER_MS = 65000;

        for (let i = 0; i < pageRows.length; i++) {
          const student = pageRows[i];

          // Add inter-request delay for all requests after the first.
          if (i > 0) await sleep(INTER_REQUEST_DELAY_MS);

          // Row already has full person data — no detail fetch needed.
          if (student && student.person) {
            detailedStudents.push(student);
            continue;
          }

          const href = student && student.href;
          if (!href) continue;

          let fetched = false;
          let retries = 0;

          while (!fetched && retries <= MAX_RETRIES_ON_429) {
            try {
              const detailResponse = await fetch(
                  `https://api-sandbox2.uk.arbor.sc${href}?format=json`,
                  {method: "GET", headers: authHeaders},
              );

              if (detailResponse.status === 429) {
                if (retries >= MAX_RETRIES_ON_429) {
                  // Exhausted retries — record position and stop this page.
                  logger.warn("Arbor rate limit hit after max retries", {
                    studentIndex: i,
                  });
                  rowsAttempted = i;
                  rateLimitedDuringDetails = true;
                  break;
                }
                // Respect Retry-After header if present, otherwise use default.
                const retryAfterHeader =
                  detailResponse.headers.get("Retry-After");
                const retryAfterMs = retryAfterHeader ?
                  parseInt(retryAfterHeader, 10) * 1000 :
                  DEFAULT_RETRY_AFTER_MS;
                logger.info("Rate limited — sleeping before retry", {
                  studentIndex: i,
                  retryAfterMs,
                  attempt: retries + 1,
                });
                await sleep(retryAfterMs);
                retries++;
                continue;
              }

              if (!detailResponse.ok) {
                // Non-rate-limit error — skip this student and move on.
                logger.warn("Arbor detail fetch failed", {
                  href,
                  status: detailResponse.status,
                });
                fetched = true; // mark done so we don't retry
                continue;
              }

              const detailJson = await detailResponse.json();
              const detailStudent = extractStudents(detailJson)[0];
              if (detailStudent) detailedStudents.push(detailStudent);
              fetched = true;
            } catch (err) {
              logger.warn("Exception fetching Arbor student detail", {
                href,
                error: err.message || "unknown",
                studentIndex: i,
              });
              fetched = true; // skip on network error
            }
          }

          // Stop processing further students if rate limit was unrecoverable.
          if (rateLimitedDuringDetails) break;
        }
        // Load pre-built class map from Firestore for className lookup.
        // Keyed by Arbor student ID. Falls back to "Unknown" if missing.
        const classMapCache = {};
        try {
          const studentIds = detailedStudents
              .map((s) => s && s.id)
              .filter((id) => id != null)
              .map(String);
          if (studentIds.length > 0) {
            // Firestore `in` max 30 values — batch larger lists.
            const batchSize = 30;
            for (let ci = 0; ci < studentIds.length; ci += batchSize) {
              const idBatch = studentIds.slice(ci, ci + batchSize);
              const mapDocs = await db.collection("arborClassMap")
                  .where(admin.firestore.FieldPath.documentId(), "in", idBatch)
                  .get();
              mapDocs.forEach((doc) => {
                classMapCache[doc.id] = doc.data().className || "Unknown";
              });
            }
          }
        } catch (cacheErr) {
          logger.warn("arborClassMap read failed — className will be Unknown", {
            error: cacheErr && cacheErr.message ? cacheErr.message : "unknown",
          });
        }

        // Map Arbor rows into app-ready student records, injecting className.
        const students = detailedStudents
            .map((student, index) => {
              const mapped = mapArborStudent(student, index);
              if (!mapped) return null;
              // Override "Unknown" with class map value when available.
              const studentId = student && student.id;
              if (studentId && classMapCache[String(studentId)]) {
                mapped.className = classMapCache[String(studentId)];
              }
              return mapped;
            })
            .filter((student) => student !== null);

        // Build Firestore payloads with school-scoped deterministic IDs.
        const slugSchool = toSlug(schoolName);
        const studentRecords = students.map((student, index) => {
          const slugClass = toSlug(student.className);
          const slugName = toSlug(student.childName);
          // School prefix prevents cross-school document collisions.
          const docId =
            `${slugSchool}_${slugClass}_${slugName}` ||
            `student_${index}`;
          return {
            documentId: docId,
            arborStudentId: student.arborStudentId || null,
            childName: student.childName,
            className: student.className,
            schoolName,
            mealSelected: student.mealSelected,
            dietaryRequirements: student.dietaryRequirements,
            source: "arbor",
          };
        });

        let writtenCount = 0;
        try {
          const batch = db.batch();

          // Queue each student record as an upsert into childRecords.
          studentRecords.forEach((record) => {
            const ref = db.collection("childRecords").doc(record.documentId);
            const docData = {
              childName: record.childName,
              className: record.className,
              schoolName: record.schoolName,
              source: record.source,
              mealSelected: record.mealSelected,
              dietaryRequirements: record.dietaryRequirements,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            };
            if (record.arborStudentId) {
              docData.arborStudentId = record.arborStudentId;
            }
            batch.set(ref, docData, {merge: true});
          });

          // Commit batch only when there is data to write.
          if (studentRecords.length > 0) {
            await batch.commit();
            writtenCount = studentRecords.length;
          }
        } catch (writeError) {
          logger.error("Firestore write failed inside function", {
            message: writeError && writeError.message ?
              writeError.message : "unknown",
          });
        }

        // Rate-limited: resume from exact row stopped at, not pageEnd.
        const nextOffset = rateLimitedDuringDetails ?
          pageStart + rowsAttempted :
          pageEnd;
        const hasMore = nextOffset < totalAvailable;

        logger.info("Arbor sync complete", {
          fetched: students.length,
          written: writtenCount,
          schoolName,
          rateLimitedDuringDetails,
          responseTopLevelKeys: Object.keys(jsonResponse || {}),
          maxRecords,
          offset,
          nextOffset,
          hasMore,
          totalAvailable,
          sampleStudentKeys:
            students.length > 0 ? Object.keys(students[0]) : [],
        });

        return {
          success: true,
          fetched: students.length,
          written: writtenCount,
          schoolName,
          rateLimitedDuringDetails,
          responseTopLevelKeys: Object.keys(jsonResponse || {}),
          maxRecords,
          offset,
          nextOffset,
          hasMore,
          totalAvailable,
          students: studentRecords,
          message: rateLimitedDuringDetails ?
            "Partially synced due to Arbor rate limiting." :
            "Arbor sync completed.",
        };
      } catch (error) {
        logger.error("Error syncing students from Arbor", {
          message: error && error.message ? error.message : "unknown",
          stack: error && error.stack ? error.stack : "no-stack",
        });

        // Return structured failure payload rather than throwing to app.
        return {
          success: false,
          fetched: 0,
          written: 0,
          schoolName,
          offset,
          hasMore: false,
          nextOffset: offset,
          totalAvailable: 0,
          message: "Arbor sync failed. Please retry in a minute.",
          errorMessage: error && error.message ? error.message : "unknown",
        };
      }
    },
);

// Callable endpoint to upload queued billing rows to Arbor.
// Clears successful queue rows from Firestore.
exports.uploadArborBillingQueue = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
      timeoutSeconds: 120,
    },
    async (request) => {
      const schoolNameFilter = (
        request.data &&
        typeof request.data.schoolName === "string" &&
        request.data.schoolName.trim()
      ) || null;

      const {username, password} = getArborCredentials();
      const authHeaders = buildArborAuthHeaders(username, password);

      try {
        const pendingSnapshot = await db.collection("arborBillingQueue")
            .where("status", "==", "pending")
            .get();

        const queueDocs = schoolNameFilter ?
          pendingSnapshot.docs.filter((doc) => {
            const item = doc.data() || {};
            return item.schoolName === schoolNameFilter;
          }) :
          pendingSnapshot.docs;

        if (queueDocs.length === 0) {
          return {
            success: true,
            schoolName: schoolNameFilter,
            queued: 0,
            uploaded: 0,
            deleted: 0,
            failed: 0,
            message: "No pending Arbor billing rows to upload.",
          };
        }

        const successes = [];
        const failures = [];

        // Upload each queue item to Arbor and collect outcome metadata.
        for (const doc of queueDocs) {
          const queueItem = doc.data() || {};

          try {
            const uploadResult = await postBillingItemToArbor(
                queueItem,
                doc.id,
                authHeaders,
            );
            if (uploadResult.ok) {
              successes.push({
                docId: doc.id,
                statusCode: uploadResult.statusCode,
              });
            } else {
              const responseObject =
                uploadResult.body && typeof uploadResult.body === "object" ?
                  uploadResult.body :
                  null;
              const responseData =
                responseObject && typeof responseObject.response === "object" ?
                  responseObject.response :
                  responseObject;
              const arborCode = responseData &&
                (responseData.code || responseData.statusCode);
              const responseErrorMessage = responseData && (
                responseData.message ||
                responseData.error ||
                responseData.reason
              );
              const effectiveStatusCode =
                typeof arborCode === "number" ?
                  arborCode :
                  uploadResult.statusCode;
              const reason =
                responseErrorMessage ||
                uploadResult.textBody ||
                "Arbor rejected billing payload";

              failures.push({
                docId: doc.id,
                statusCode: effectiveStatusCode,
                reason: String(reason),
                response: responseObject,
                attemptName: uploadResult.attemptName || null,
                attemptResults: uploadResult.attemptResults || [],
              });
            }
          } catch (uploadError) {
            const caughtMessage = uploadError && uploadError.message ?
              uploadError.message :
              "unknown";
            failures.push({
              docId: doc.id,
              statusCode: 0,
              reason: caughtMessage,
              errorMessage: caughtMessage,
            });
          }
        }

        // Delete successfully uploaded queue docs in batches of <=500.
        let deletedCount = 0;
        for (let i = 0; i < successes.length; i += 500) {
          const batch = db.batch();
          successes.slice(i, i + 500).forEach((success) => {
            batch.delete(db.collection("arborBillingQueue").doc(success.docId));
          });
          await batch.commit();
          deletedCount += successes.slice(i, i + 500).length;
        }

        const historyRef = db.collection("billingUploadHistory").doc();
        await historyRef.set({
          schoolName: schoolNameFilter,
          queued: queueDocs.length,
          uploaded: successes.length,
          deleted: deletedCount,
          failed: failures.length,
          uploadedAt: admin.firestore.FieldValue.serverTimestamp(),
          source: "arborSandbox",
          hasFailures: failures.length > 0,
          successfulDocIds: successes.map((item) => item.docId),
          failedItems: failures.map((failure) => ({
            docId: failure.docId,
            statusCode: failure.statusCode,
            reason: failure.reason,
            attemptName: failure.attemptName || null,
            attemptResults: failure.attemptResults || [],
          })),
        });

        const firstFailureReason = failures[0] ?
          String(failures[0].reason || "") :
          null;

        logger.info("Arbor billing upload complete", {
          schoolName: schoolNameFilter,
          queued: queueDocs.length,
          uploaded: successes.length,
          deleted: deletedCount,
          failed: failures.length,
          historyDocId: historyRef.id,
          failedDocIds: failures.map((failure) => failure.docId),
          firstFailureReason,
          firstFailureStatusCode: failures[0] ? failures[0].statusCode : null,
          firstFailureAttemptName: failures[0] ? failures[0].attemptName : null,
        });

        return {
          success: failures.length === 0,
          schoolName: schoolNameFilter,
          queued: queueDocs.length,
          uploaded: successes.length,
          deleted: deletedCount,
          failed: failures.length,
          historyCollection: "billingUploadHistory",
          historyDocId: historyRef.id,
          firstFailureReason,
          firstFailureStatusCode: failures[0] ? failures[0].statusCode : null,
          firstFailureAttemptName: failures[0] ? failures[0].attemptName : null,
          failedItems: failures,
          message: failures.length === 0 ?
            "Arbor billing queue uploaded successfully." :
            "Some Arbor billing queue rows failed to upload.",
        };
      } catch (error) {
        logger.error("Arbor billing upload failed", {
          message: error && error.message ? error.message : "unknown",
          stack: error && error.stack ? error.stack : "no-stack",
          schoolName: schoolNameFilter,
        });

        return {
          success: false,
          schoolName: schoolNameFilter,
          queued: 0,
          uploaded: 0,
          deleted: 0,
          failed: 0,
          message: "Arbor billing upload failed.",
          errorMessage: error && error.message ? error.message : "unknown",
        };
      }
    },
);

// ---------------------------------------------------------------------------
// GraphQL helper: queries Arbor's /graphql/query endpoint.
// The WAF requires a browser User-Agent; Basic auth is passed in the header.
// ---------------------------------------------------------------------------
/**
 * Sends a GraphQL query to Arbor and returns parsed JSON.
 *
 * @param {string} query GraphQL query string.
 * @param {Object} authHeaders Basic-auth headers from buildArborAuthHeaders.
 * @return {Promise<Object>} Parsed GraphQL response.
 */
async function arborGraphQL(query, authHeaders) {
  const resp = await fetch(
      "https://api-sandbox2.uk.arbor.sc/graphql/query",
      {
        method: "POST",
        headers: {
          ...authHeaders,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({query}),
      },
  );
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`GraphQL HTTP ${resp.status}: ${text.substring(0, 200)}`);
  }
  return resp.json();
}

// ---------------------------------------------------------------------------
// Shared helper: uses GraphQL MealRotationMenuChoice to pull today's specific
// daily meal choices and upsert them into childRecords.  Falls back to
// MealChoice standing orders when no rotation choices exist for the date.
// Runs server-side (no client timeout) — used by the scheduled function.
// ---------------------------------------------------------------------------
/**
 * Syncs today's meal choices from Arbor into Firestore for a school.
 *
 * @param {string} schoolName School display name.
 * @param {Object} authHeaders Arbor auth headers.
 * @param {string} targetDate ISO date string YYYY-MM-DD.
 * @return {Promise<number>} Count of updated childRecords.
 */
// Minimal Student fields for meal sync.
const STUDENT_GQL_FIELDS = `
  ... on Student {
    studentId
    displayName
    registrationForm { shortName }
    dietaryRequirements {
      dietaryRequirementType { displayName }
    }
  }
`;

/**
 * Extracts a clean student record from a GraphQL attendee + mealProvision.
 *
 * @param {{attendee: *, mealProvision: *}} row Raw GraphQL row.
 * @return {{studentId: string, displayName: string,
 *   className: string, mealProvisionName: string,
 *   mealName: string, dietaryRequirements: string[]}|null} Record or null.
 */
function extractMealChoiceRecord(row) {
  const att = row.attendee;
  if (!att || !att.studentId) return null;

  const displayName = (att.displayName || `Student ${att.studentId}`).trim();
  const className =
    (att.registrationForm && att.registrationForm.shortName) || "";
  const mealProvisionName =
    (row.mealProvision && row.mealProvision.mealProvisionName) || "School Meal";
  const mealName =
    (row.mealProvision &&
      row.mealProvision.meal &&
      row.mealProvision.meal.mealName) ||
    "";

  const dietaryRequirements = (att.dietaryRequirements || [])
      .map((req) => req && req.dietaryRequirementType &&
        req.dietaryRequirementType.displayName)
      .filter(Boolean)
      .map((name) => String(name).trim());

  return {
    studentId: String(att.studentId),
    displayName,
    className,
    mealProvisionName,
    mealName,
    dietaryRequirements,
  };
}

/**
 * Fetches today's meal choices from Arbor via GraphQL and upserts them as
 * full childRecords into Firestore.
 * Tries MealRotationMenuChoice first (specific daily choices), then falls
 * back to MealChoice (standing weekly orders).
 *
 * @param {string} schoolName Name tag stamped on every written record.
 * @param {*} authHeaders Basic-auth headers for Arbor.
 * @param {string} targetDate YYYY-MM-DD date to query.
 * @return {Promise<number>} Number of records written.
 */
async function syncMealChoicesForSchool(schoolName, authHeaders, targetDate) {
  // Map: arborStudentId -> {displayName, className, mealProvisionName}
  const records = {};
  const PAGE_SIZE = 200;
  let pageNum = 1;
  let totalFetched = 0;

  const studentFragment = STUDENT_GQL_FIELDS;

  // Step 1: try MealRotationMenuChoice (specific daily dish choices).
  let morePages = true;
  while (morePages) {
    const gqlQuery = `{
      MealRotationMenuChoice(
        mealChoiceDate: "${targetDate}"
        page_size: ${PAGE_SIZE}
        page_num: ${pageNum}
      ) {
        mealChoiceDate
        mealProvision {
          mealProvisionName
          meal { mealName }
        }
        attendee { ${studentFragment} }
      }
    }`;

    const result = await arborGraphQL(gqlQuery, authHeaders);
    const rows = (result.data && result.data.MealRotationMenuChoice) || [];
    if (rows.length === 0) break;

    totalFetched += rows.length;
    if (pageNum === 1 && rows.length > 0) {
      // Log the first raw row so we can verify field availability.
      logger.info("MealRotationMenuChoice sample row", {
        row: JSON.stringify(rows[0]).substring(0, 300),
      });
    }
    rows.forEach((row) => {
      const rec = extractMealChoiceRecord(row);
      if (!rec) return;
      if (!records[rec.studentId]) records[rec.studentId] = rec;
    });

    if (rows.length < PAGE_SIZE) {
      morePages = false;
      break;
    }
    pageNum++;
    await sleep(200);
  }

  // Step 2: fall back to MealChoice when no daily-specific data found.
  // eslint-disable-next-line
  if (totalFetched === 0) {
    logger.info("No MealRotationMenuChoice; falling back to MealChoice", {
      targetDate, schoolName,
    });

    const dayIndex = new Date(targetDate).getDay(); // 0=Sun ... 6=Sat
    const dayArgs = [
      "appliesSunday: true",
      "appliesMonday: true",
      "appliesTuesday: true",
      "appliesWednesday: true",
      "appliesThursday: true",
      "appliesFriday: true",
      "appliesSaturday: true",
    ][dayIndex];

    pageNum = 1;
    let moreStanding = true;
    while (moreStanding) {
      // Note: effectiveDate/endDate filters intentionally omitted.
      // In sandbox the standing-order date ranges don't cover today,
      // so the filters would exclude all test data.
      // In production, MealRotationMenuChoice handles today's specific
      // choices and this fallback is rarely reached.
      const gqlQuery = `{
        MealChoice(
          ${dayArgs}
          page_size: ${PAGE_SIZE}
          page_num: ${pageNum}
        ) {
          mealProvision {
            mealProvisionName
            meal { mealName }
          }
          attendee { ${studentFragment} }
        }
      }`;

      const result = await arborGraphQL(gqlQuery, authHeaders);
      const rows = (result.data && result.data.MealChoice) || [];
      if (rows.length === 0) break;

      if (pageNum === 1 && rows.length > 0) {
        logger.info("MealChoice sample row", {
          row: JSON.stringify(rows[0]).substring(0, 300),
        });
      }
      rows.forEach((row) => {
        const rec = extractMealChoiceRecord(row);
        if (!rec) return;
        if (!records[rec.studentId]) records[rec.studentId] = rec;
      });

      if (rows.length < PAGE_SIZE) {
        moreStanding = false;
        break;
      }
      pageNum++;
      await sleep(200);
    }
  }

  const studentIds = Object.keys(records);
  if (studentIds.length === 0) {
    logger.info("No meal choices found for date", {targetDate, schoolName});
    return 0;
  }

  // Upsert one full childRecord per student.
  // Document ID: schoolSlug_classSlug_nameSlug (deterministic, school-scoped).
  const slugSchool = toSlug(schoolName);
  const writeBatch = db.batch();
  let written = 0;

  studentIds.forEach((studentId) => {
    const rec = records[studentId];
    const slugClass = toSlug(rec.className || "unknown");
    const slugName = toSlug(rec.displayName || `student_${studentId}`);
    const docId = `${slugSchool}_${slugClass}_${slugName}`;

    const docRef = db.collection("childRecords").doc(docId);
    writeBatch.set(docRef, {
      childName: rec.displayName,
      className: rec.className,
      schoolName,
      mealSelected: rec.mealProvisionName,
      mealName: rec.mealName,
      dietaryRequirements: rec.dietaryRequirements,
      arborStudentId: studentId,
      served: false,
      source: "arbor",
      mealChoiceSyncedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, {merge: true});
    written++;
  });

  await writeBatch.commit();
  logger.info("Meal choices upserted", {schoolName, written, targetDate});
  return written;
}

// ---------------------------------------------------------------------------
// Helper: Returns today's ISO date string (YYYY-MM-DD) in Europe/London time.
// ---------------------------------------------------------------------------
/**
 * Returns today's date as YYYY-MM-DD in Europe/London time.
 *
 * @param {Date|null} dateOverride Optional override date.
 * @return {string} ISO date string.
 */
function londonDateString(dateOverride) {
  const d = dateOverride ? new Date(dateOverride) : new Date();
  return d.toLocaleDateString("en-GB", {
    timeZone: "Europe/London",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).split("/").reverse().join("-");
}

// ---------------------------------------------------------------------------
// Callable: syncTodaysMealChoices
// Uses GraphQL MealRotationMenuChoice (specific daily choices) or falls back
// to MealChoice standing orders.  Single date-filtered query — no per-student
// API calls needed.  Returns {updated, targetDate, message}.
// ---------------------------------------------------------------------------
exports.syncTodaysMealChoices = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
      // GraphQL returns all pages quickly — 60s is ample.
      timeoutSeconds: 60,
      memory: "256MiB",
    },
    async (request) => {
      const schoolName = (
        request.data &&
        typeof request.data.schoolName === "string" &&
        request.data.schoolName.trim()
      ) || "Arbor Sandbox";

      const targetDate = (
        request.data &&
        typeof request.data.targetDate === "string" &&
        request.data.targetDate.trim()
      ) || londonDateString();

      const {username, password} = getArborCredentials();
      const authHeaders = buildArborAuthHeaders(username, password);

      try {
        const updated =
          await syncMealChoicesForSchool(schoolName, authHeaders, targetDate);

        logger.info("syncTodaysMealChoices callable complete", {
          schoolName, targetDate, updated,
        });

        return {
          success: true,
          schoolName,
          targetDate,
          written: updated,
          message: `Meal choices sync complete. ${updated} records written.`,
        };
      } catch (error) {
        const msg = error && error.message ? error.message : "unknown";
        logger.error("syncTodaysMealChoices error", {
          error: msg, schoolName, targetDate,
        });
        return {
          success: false,
          written: 0,
          targetDate,
          message: `Meal choices sync failed: ${msg}`,
        };
      }
    },
);
