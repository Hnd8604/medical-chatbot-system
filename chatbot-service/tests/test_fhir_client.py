import unittest

import httpx

from fhir.client import FhirClient, FhirNotFoundError


class FhirClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_get_metadata_success(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(str(request.url), "http://fhir.test/metadata")
            return httpx.Response(200, json={"resourceType": "CapabilityStatement"})

        client = FhirClient(
            base_url="http://fhir.test",
            transport=httpx.MockTransport(handler),
        )

        result = await client.get_metadata()

        self.assertEqual(result["resourceType"], "CapabilityStatement")

    async def test_patient_not_found(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(404, json={"resourceType": "OperationOutcome"})

        client = FhirClient(
            base_url="http://fhir.test",
            transport=httpx.MockTransport(handler),
        )

        with self.assertRaises(FhirNotFoundError):
            await client.get_patient("missing")

    async def test_search_patient_resources_uses_safe_count_and_patient_ref(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.params["patient"], "Patient/BN2026-00001")
            self.assertEqual(request.url.params["_count"], "5")
            self.assertEqual(request.url.params["_sort"], "-date")
            return httpx.Response(200, json={"resourceType": "Bundle", "entry": []})

        client = FhirClient(
            base_url="http://fhir.test",
            transport=httpx.MockTransport(handler),
        )

        result = await client.search_patient_resources(
            "Observation",
            "BN2026-00001",
            count=5,
            sort="-date",
        )

        self.assertEqual(result["resourceType"], "Bundle")

    async def test_search_patients_uses_count(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(str(request.url.copy_with(query=None)), "http://fhir.test/Patient")
            self.assertEqual(request.url.params["_count"], "20")
            return httpx.Response(200, json={"resourceType": "Bundle", "entry": []})

        client = FhirClient(
            base_url="http://fhir.test",
            transport=httpx.MockTransport(handler),
        )

        result = await client.search_patients(count=20)

        self.assertEqual(result["resourceType"], "Bundle")

    async def test_search_patients_uses_search_criteria(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.params["_count"], "10")
            # Tên nhiều từ -> nhiều tham số `name` lặp lại (HAPI AND các phần tên).
            self.assertEqual(request.url.params.get_list("name"), ["Nguyen", "Van", "A"])
            self.assertEqual(request.url.params["phone"], "0900000001")
            self.assertEqual(request.url.params["birthdate"], "2003-01-01")
            self.assertEqual(request.url.params["identifier"], "BN001")
            return httpx.Response(200, json={"resourceType": "Bundle", "entry": []})

        client = FhirClient(
            base_url="http://fhir.test",
            transport=httpx.MockTransport(handler),
        )

        result = await client.search_patients(
            count=10,
            name="Nguyen Van A",
            phone="0900000001",
            birth_date="2003-01-01",
            identifier="BN001",
        )

        self.assertEqual(result["resourceType"], "Bundle")

    async def test_search_patients_flexible_falls_back_to_n_minus_one_subset(self) -> None:
        seen_names = []

        def handler(request: httpx.Request) -> httpx.Response:
            names = tuple(request.url.params.get_list("name"))
            seen_names.append(names)
            # Chỉ khớp khi bỏ token đệm "Quang": subset ("Hoang", "Phong").
            if names == ("Hoang", "Phong"):
                return httpx.Response(200, json={
                    "resourceType": "Bundle",
                    "entry": [{"resource": {"resourceType": "Patient", "id": "BN2026-00002"}}],
                })
            return httpx.Response(200, json={"resourceType": "Bundle", "entry": []})

        client = FhirClient(
            base_url="http://fhir.test",
            transport=httpx.MockTransport(handler),
        )

        result = await client.search_patients_flexible(count=5, name="Hoang Quang Phong")

        # AND đủ token rỗng -> thử các subset n-1 token (bỏ đúng 1), KHÔNG tụt xuống 1 token.
        self.assertEqual(
            seen_names,
            [("Hoang", "Quang", "Phong"), ("Quang", "Phong"), ("Hoang", "Phong")],
        )
        self.assertTrue(result.get("entry"))
        self.assertEqual(result["entry"][0]["resource"]["id"], "BN2026-00002")

    async def test_search_patients_flexible_does_not_match_single_token(self) -> None:
        # Regression: "Nguyen Van An" không được khớp nhầm bệnh nhân chỉ trùng token "An".
        seen_names = []

        def handler(request: httpx.Request) -> httpx.Response:
            names = tuple(request.url.params.get_list("name"))
            seen_names.append(names)
            # DB chỉ có người khớp đúng một token "An" (vd Phan Bá An).
            if names == ("An",):
                return httpx.Response(200, json={
                    "resourceType": "Bundle",
                    "entry": [{"resource": {"resourceType": "Patient", "id": "BN2026-00001"}}],
                })
            return httpx.Response(200, json={"resourceType": "Bundle", "entry": []})

        client = FhirClient(
            base_url="http://fhir.test",
            transport=httpx.MockTransport(handler),
        )

        result = await client.search_patients_flexible(count=5, name="Nguyen Van An")

        # Không được trả về bệnh nhân nào (không tự đoán theo 1 token đơn).
        self.assertFalse(result.get("entry"))
        # Và không bao giờ thử tìm với chỉ 1 token.
        self.assertNotIn(("An",), seen_names)


if __name__ == "__main__":
    unittest.main()
