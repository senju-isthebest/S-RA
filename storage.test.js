const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after } = require("node:test");
const fs = require("node:fs");
const { ref, uploadBytes, getBytes, deleteObject } = require("firebase/storage");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const [emulatorHost, emulatorPortStr] = (
  process.env.FIREBASE_STORAGE_EMULATOR_HOST || "127.0.0.1:9199"
).split(":");
const emulatorPort = Number(emulatorPortStr);

before(async () => {
  const rules = fs.readFileSync("./storage.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    storage: { rules, host: emulatorHost, port: emulatorPort },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

test("signed-in owner can upload and download a supported textbook file", async () => {
  const storage = testEnv.authenticatedContext("alice").storage();
  const fileRef = ref(storage, "users/alice/books/book_1/source");
  await assertSucceeds(uploadBytes(fileRef, Buffer.from("sample pdf bytes"), {
    contentType: "application/pdf",
  }));
  const bytes = await assertSucceeds(getBytes(fileRef));
  if (Buffer.from(bytes).toString() !== "sample pdf bytes") {
    throw new Error("Downloaded source bytes did not match upload.");
  }
  await assertSucceeds(deleteObject(fileRef));
});

test("other users cannot read or upload into an owner's book path", async () => {
  const bobStorage = testEnv.authenticatedContext("bob").storage();
  const alicePath = ref(bobStorage, "users/alice/books/book_1/source");
  await assertFails(uploadBytes(alicePath, Buffer.from("steal"), {
    contentType: "application/pdf",
  }));
  await assertFails(getBytes(alicePath));
});

test("storage rejects unsupported MIME types and oversized files", async () => {
  const aliceStorage = testEnv.authenticatedContext("alice").storage();
  const invalidType = ref(aliceStorage, "users/alice/books/book_2/source");
  await assertFails(uploadBytes(invalidType, Buffer.from("script"), {
    contentType: "application/javascript",
  }));
  const tooLarge = ref(aliceStorage, "users/alice/books/book_3/source");
  const payload = new Uint8Array(80 * 1024 * 1024 + 1);
  await assertFails(uploadBytes(tooLarge, payload, { contentType: "application/pdf" }));
});
