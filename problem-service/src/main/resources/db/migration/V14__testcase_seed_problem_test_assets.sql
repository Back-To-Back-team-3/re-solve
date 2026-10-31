-- 150개 테스트 파일 메타 정보는 LocalStack 업로드로 확보한 실제 object_key·sha256·size_bytes를 그대로 반영한다(그릴링 Q1~Q3·Q5~Q6).
-- problem_id는 문제 30개가 V13에서 처음 채워져 AUTO_INCREMENT가 삽입 순서대로 1~30이 된다는 전제를 그대로 쓴다(V13과 동일 패턴).
-- object_version은 버전 관리 미사용 버킷이라 NULL, status는 검증 파이프라인이 아직 없어 LINKED로 직접 지정한다(V13 problem_body_assets와 동일 전제).
-- 전역 순번(1~150) = (problem_id - 1) * 5 + test_order. 공개(1~2)·숨김(3~5) 구성은 V15에서 problem_revision_tests로 연결한다.

INSERT INTO problem_test_assets (
    problem_id, owner_id, status, object_key, object_version, sha256, size_bytes,
    upload_expires_at, uploaded_at, upload_finished_at, created_at, updated_at
)
SELECT p.id, 1, 'LINKED', t.object_key, NULL, t.sha256, t.size_bytes,
    '2026-11-01 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000',
    '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'
FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS seq
    FROM problems
    WHERE created_at = '2026-10-31 00:00:00.000000'
) p
JOIN (
    SELECT 1 AS seq, 1 AS problem_seq, 'test-cases/1.json' AS object_key, '4df4e698b143abd0210a5f8c0bbbda27b6fdf24a70bbeadcd76ba20658896607' AS sha256, 92 AS size_bytes
    UNION ALL SELECT 2, 1, 'test-cases/2.json', '13c96f50fa619938602cade57711c5dff8f7b46026af69170d6178b326600762', 78
    UNION ALL SELECT 3, 1, 'test-cases/3.json', 'e60d46f0535fc05729ef1a737534ac1d39de4fecac70e62d0c89f9ba92bfabea', 65
    UNION ALL SELECT 4, 1, 'test-cases/4.json', '45ff6a1909482bf11d2efdba8f63e3a87f9bd502b5189a7faa2e6d8becc3cb51', 68
    UNION ALL SELECT 5, 1, 'test-cases/5.json', 'e22077f51e7f7026ae2367065a97bda1f216ffd5b99c9c7c8a59c2e3b6751c49', 74
    UNION ALL SELECT 6, 2, 'test-cases/6.json', 'db6c38807e78c5ec75a71d8649323d5bd0fa5d3f275cd8a7b6b2aeb1acfd8e47', 94
    UNION ALL SELECT 7, 2, 'test-cases/7.json', '6583a3d62b55da9cef5b2b724f4f677fdaa9c8ed5b836cd629d4e1314f8311b2', 86
    UNION ALL SELECT 8, 2, 'test-cases/8.json', '94189e9dcf7064efc0fb5fd7ebed666afdb4b72f0d4e1e182d1024b474438f60', 68
    UNION ALL SELECT 9, 2, 'test-cases/9.json', 'f35a215c42d833c69bfce33950c28f1e09ef82e122c972f220b6d8df740d0184', 68
    UNION ALL SELECT 10, 2, 'test-cases/10.json', 'd4ed2996a072bfd2ea5eebdacd91a5e6a8d2947eb8fb05f654eaa891ed7e67b8', 76
    UNION ALL SELECT 11, 3, 'test-cases/11.json', '8e44f946b0f37c8fd0b3d6198bdccd9511ae0e9835082ef3e99195d5659975a3', 84
    UNION ALL SELECT 12, 3, 'test-cases/12.json', '5e4f40887714c78b9c2c033ccbf3d0b6fc433c3c7a325aa02ad1de2709a792dc', 85
    UNION ALL SELECT 13, 3, 'test-cases/13.json', '51d0b809a15997ac88cea5008f8ca9bb7939d7342514bf053aa341bc2e4f2f55', 83
    UNION ALL SELECT 14, 3, 'test-cases/14.json', 'e2f16b7c4c412143cfee630acfb471a696f34f03f3bd7b5d7aa872f808c43512', 84
    UNION ALL SELECT 15, 3, 'test-cases/15.json', '26d70a664c8602fa79d03d39b9310070eb1c88b674f87143673480d60bbdfb92', 83
    UNION ALL SELECT 16, 4, 'test-cases/16.json', '4455e3fca9a3756c23580eee50e7237ef50534e2ef04c0fcce46f7ac9de432fd', 130
    UNION ALL SELECT 17, 4, 'test-cases/17.json', 'e6728fd800312e89498b963e028fc7b242bcdfaa427923f50e24f0248e4947b3', 122
    UNION ALL SELECT 18, 4, 'test-cases/18.json', '287fcea08a75873d0e301d94b583baaeaf29a92552f140f57e62c2cd4a320553', 106
    UNION ALL SELECT 19, 4, 'test-cases/19.json', '2dd40abe1812bcf45917cce7b829174fc78f93539a5d1646a4b6e4f5158d7175', 114
    UNION ALL SELECT 20, 4, 'test-cases/20.json', '18daa200f3d9046c2dc3d549e8fac334bf18e6af3840b5009021578218287c99', 138
    UNION ALL SELECT 21, 5, 'test-cases/21.json', '4775f12aabfe1d87c162f3735d248da201b568be90c0616bdc42867957c68508', 116
    UNION ALL SELECT 22, 5, 'test-cases/22.json', '987e79874f857ad43b1a153911a3d57ebff4e6bec9b04120a3143bff383fde98', 106
    UNION ALL SELECT 23, 5, 'test-cases/23.json', '5ef133c34d3e861117986269d7ff593e4b5c9f0d87e399a383899eac409fe48b', 98
    UNION ALL SELECT 24, 5, 'test-cases/24.json', 'f037be4b667186525d265f5844e95d7cf892ad9c253aae7404dea25f781a36ed', 99
    UNION ALL SELECT 25, 5, 'test-cases/25.json', '55157102b35a41fe418d94dbc042d3e4c9aafbb755001f0168df217626af69a4', 104
    UNION ALL SELECT 26, 6, 'test-cases/26.json', 'ff16234e39a272ff6457d5fccd9d6682e97eb79e682593080772a21b48e945f7', 106
    UNION ALL SELECT 27, 6, 'test-cases/27.json', '1ee4df2120437b873a21213715c7f17fa6e61857c9f59eabb073c1c4dd3fead8', 96
    UNION ALL SELECT 28, 6, 'test-cases/28.json', '64505b8cf2951f05c2eb5a5965b640fedfc7f3e2c6d357ad364c7e4b81c41520', 89
    UNION ALL SELECT 29, 6, 'test-cases/29.json', '79cbb00256eaa54a90c0de81e103699681db72c52712b4f96e02b20885b2d435', 89
    UNION ALL SELECT 30, 6, 'test-cases/30.json', '98494d40a424d7f1c7befab45860ca1021a52ad8eb665d70cc53eb30a4b1a779', 93
    UNION ALL SELECT 31, 7, 'test-cases/31.json', 'e094ef94c4efe2d9c0d49192ca0ff400d6e9017699ce60d19ce94e388db5c32a', 129
    UNION ALL SELECT 32, 7, 'test-cases/32.json', 'fe95f3ef16d4d3a69391a7557741d248628563fcf00d841a0a8acc6046199f81', 120
    UNION ALL SELECT 33, 7, 'test-cases/33.json', 'e16d3ace6a8bd1c84c08e147cd8fbb1dd7677762bfacdf929060ce4b1c6e7948', 88
    UNION ALL SELECT 34, 7, 'test-cases/34.json', 'f7e2ca7a77b88fe4fe6abd8fb0efde44d08fb47454cf86f38c0dfed9b71ed099', 102
    UNION ALL SELECT 35, 7, 'test-cases/35.json', '098da46d1bb4f3d5a599080eea1d41fc97de5f01eb33aeef82774499e434b76c', 119
    UNION ALL SELECT 36, 8, 'test-cases/36.json', '7477a6795e38dc40bb7c6e3742473aee6eafb09eb17653b0b6e280d0a5a0f719', 79
    UNION ALL SELECT 37, 8, 'test-cases/37.json', '1955bec6cf85903fa58762d7118725368b1d95801fa9da1ec08bdf45ca05a390', 76
    UNION ALL SELECT 38, 8, 'test-cases/38.json', 'f927c14abfbe31587148f1e15a94c6e06131212709d73cb1d7754ef60c59866c', 70
    UNION ALL SELECT 39, 8, 'test-cases/39.json', 'ff706eac21bf78522bc1167b3c6132efafced95e5a627fd57e687d5f5913c785', 70
    UNION ALL SELECT 40, 8, 'test-cases/40.json', 'f850264c2f6cf7ccd3a046f3a19198a9ac05df9e77b283d4a6299bb5aa7c8b34', 76
    UNION ALL SELECT 41, 9, 'test-cases/41.json', 'db9086d3c401181ee07621dcc95ac618e349cd70ddd74126b266b8cc6898dd1a', 96
    UNION ALL SELECT 42, 9, 'test-cases/42.json', '793ea366de93c5eb86d2a5efc34b07055175287c636a75315ec318fd5ef1322a', 84
    UNION ALL SELECT 43, 9, 'test-cases/43.json', '8afbec37399f7cd961b8ab303b4c0271b7d33a6ee804df365b543d8661c32ef7', 96
    UNION ALL SELECT 44, 9, 'test-cases/44.json', '4bcd3cf54e8a13f990db79f7e869736268221485a8cd7ac00fa1c2fe33e0683f', 96
    UNION ALL SELECT 45, 9, 'test-cases/45.json', 'e2972ad0468c7e318acb8a4585ae83729989648534c30ab94db9a92d2ebfb02f', 84
    UNION ALL SELECT 46, 10, 'test-cases/46.json', 'bf87741c9b68517ef06418dd6bb129e503bd7263642d243951b2582a1fd4fb36', 114
    UNION ALL SELECT 47, 10, 'test-cases/47.json', '1c9502de8b7864fdd1ebb0b2e9cb79774bdca9fe38e20460db21af3d14ab3e16', 105
    UNION ALL SELECT 48, 10, 'test-cases/48.json', 'fee00f9de4f025c6f727a90508f0bf84a3c4d73dc85994b28456d0e596c0ea7a', 102
    UNION ALL SELECT 49, 10, 'test-cases/49.json', '6cad41be5838393f5888ebde1040f25d1c056e117e0f508d1d571549ca4dd829', 105
    UNION ALL SELECT 50, 10, 'test-cases/50.json', '0908aea6f52cfc36433f05d64e4a8fcb16987c83a89fd6500f51eddead9761ef', 104
    UNION ALL SELECT 51, 11, 'test-cases/51.json', '75f17235cc4d1851483ed0c0677543c2516f443a6ac9c3948f943d4f16c772db', 88
    UNION ALL SELECT 52, 11, 'test-cases/52.json', '0ab399cb808ff9ba48ca8e23067f675bd66fdb71577c74a5141a402cfeb7ade0', 89
    UNION ALL SELECT 53, 11, 'test-cases/53.json', '78878ad0291dcff1e622d3289ba6bc6beb0d9afaf59d7e5a4e9b92ac2376f9e7', 90
    UNION ALL SELECT 54, 11, 'test-cases/54.json', '7c4542ec0d10d42bf79bae08ea6b8795b63dbb5b440bf093106d033815aff8ee', 84
    UNION ALL SELECT 55, 11, 'test-cases/55.json', '36335f3fe05cd23fc97f3cfb2245733f99015bfc576c3c270275d147bf9835f1', 88
    UNION ALL SELECT 56, 12, 'test-cases/56.json', '85ce123e020ce65cec52f56b9148a5ac5fd0b5a4bdd5193438b0f06400e5a1cc', 139
    UNION ALL SELECT 57, 12, 'test-cases/57.json', '50c93aa5e0340d291c5859532b35443d1eef684220721cf02d056812f35e1438', 139
    UNION ALL SELECT 58, 12, 'test-cases/58.json', 'e2dad35b52dede128a0d5fc85a6f6f0f74b490a6730488440137bfdef122272f', 131
    UNION ALL SELECT 59, 12, 'test-cases/59.json', '12eab30c5f35aa6980895ec4546c97ed476dbafae99fc8df0e481940f746c9c6', 123
    UNION ALL SELECT 60, 12, 'test-cases/60.json', '3957f70aeb8e4a387f6f980560e8a21219c21fd6492dc5523ff6d1f442308794', 117
    UNION ALL SELECT 61, 13, 'test-cases/61.json', 'd02b950a1e9ae8919b10db793ce498767cd26b85fed8e1beb114e179acc6bf94', 152
    UNION ALL SELECT 62, 13, 'test-cases/62.json', '8ccc645783a260d78c6b3eb432e31f848cb4fb946706fc352214303c2dd20468', 136
    UNION ALL SELECT 63, 13, 'test-cases/63.json', 'e6c16aa75644d1533b8af8d1bebd8688bcfba7e40092fe65dfc5a20952a5da83', 128
    UNION ALL SELECT 64, 13, 'test-cases/64.json', 'c46cb78148bd6dbf45dad87b2189e396ae332e3f09c56698156a20f555368658', 168
    UNION ALL SELECT 65, 13, 'test-cases/65.json', '59d172cf7192a4295bc5407eefd8659243ff977b7436fca0a7a1063b5589e997', 128
    UNION ALL SELECT 66, 14, 'test-cases/66.json', '6cd713b141032c432a3e81f39a6cb15a59c378b554d298612e406742bf14cae8', 107
    UNION ALL SELECT 67, 14, 'test-cases/67.json', '420ae18fc6f1165edd1f8cfe3fa785eed85a7292244d37be6a4075a2e29e1348', 98
    UNION ALL SELECT 68, 14, 'test-cases/68.json', '0ef0ec903822b63ae77a6f42e1a6f9d5a9482c6417b9975be3f92fe03326694f', 97
    UNION ALL SELECT 69, 14, 'test-cases/69.json', 'f0bc2481f286a91898ef449f1e1b1b9a9c7b0cf99b107450b4820d87072231e9', 99
    UNION ALL SELECT 70, 14, 'test-cases/70.json', '6630ced4704634f5f1e658de413a2fc341dfbd56e378855ca7a72d7d0a8b8920', 95
    UNION ALL SELECT 71, 15, 'test-cases/71.json', 'd35643dc0f582c5197b721d239db86ed77c84d4a0046f2249d614cbc466197bb', 73
    UNION ALL SELECT 72, 15, 'test-cases/72.json', '457aaa47e414aa831c2f3eb1733363aec54fb814c9bc9fa63adba3a527b524a2', 70
    UNION ALL SELECT 73, 15, 'test-cases/73.json', '5737c9382884bc37af9b1ca1711a9672248bcdcec26acdfd649943dcb2228a7a', 66
    UNION ALL SELECT 74, 15, 'test-cases/74.json', 'c15555be6458080c8f7656deb7df295866b87a7793f2a9af9447bdff0b830454', 72
    UNION ALL SELECT 75, 15, 'test-cases/75.json', '8f26befb6c97eb8bc8f13aad6a5f9110bb31a871d1fe287abe3e1ebe8af5589a', 76
    UNION ALL SELECT 76, 16, 'test-cases/76.json', 'f1ca28984bec6a2781ae9d94296435c743e29a98032b5ff432dbafcfc56e1e25', 133
    UNION ALL SELECT 77, 16, 'test-cases/77.json', '473768bec82755e782266175d32cb6e098be56185add74b97c56a1ac7eac7dce', 113
    UNION ALL SELECT 78, 16, 'test-cases/78.json', 'eb2a081ef0a4b04e9f0caac67e58bb50f26b5cc7135a69b90eb8821d0a122e70', 96
    UNION ALL SELECT 79, 16, 'test-cases/79.json', 'bddf554180e0433aa9cf62563f4a929d2387dffc6c62bc792799d9c08f6313e2', 130
    UNION ALL SELECT 80, 16, 'test-cases/80.json', 'ec9e6c93f4a36a25a10f7e4bc6ec90ef4a752ddbb633a8c245fd9b78d8768056', 130
    UNION ALL SELECT 81, 17, 'test-cases/81.json', '6119c6cabb03f9c585ed9d0f59adf39177015fcb632d40178d3fa3abf20f7ab1', 144
    UNION ALL SELECT 82, 17, 'test-cases/82.json', '8210cb82d31ab48d0868b3b40102c4190406a528e5212ff40a2a6af5392aed4f', 131
    UNION ALL SELECT 83, 17, 'test-cases/83.json', 'b3e013e48e942a0dd981ef99e4a1402553a4954469b1ba7737fe71bc3fc8329e', 115
    UNION ALL SELECT 84, 17, 'test-cases/84.json', '210bb2148f15959795db2f588ea406dac09868b82f989376a08707416ccd850c', 127
    UNION ALL SELECT 85, 17, 'test-cases/85.json', 'b7b409512c79e7dfd620867acf929f6e02ba13edd284a9bd6c90c84c56e644ec', 145
    UNION ALL SELECT 86, 18, 'test-cases/86.json', '1a66b92e291b41794a0cb31db1d5307152ed88d886ac0a005bb79e3ae75d30f9', 106
    UNION ALL SELECT 87, 18, 'test-cases/87.json', '895d2d17f2ba59143b289298b182eacaa95be6869358df3df12c5f6c852215b9', 100
    UNION ALL SELECT 88, 18, 'test-cases/88.json', 'f5bbb360eddf713458bb2132ec7f950175f301ce82184f1843fd0197c88e491a', 106
    UNION ALL SELECT 89, 18, 'test-cases/89.json', '730cc8cf2092bfb3bc6bf2a4d4266a8085f9e08e75ec45c273ed72aec58d939b', 101
    UNION ALL SELECT 90, 18, 'test-cases/90.json', 'afa54cfb7dfdcc47c8ff07276c19d69624467d68c560139f489e9013ba1694d1', 118
    UNION ALL SELECT 91, 19, 'test-cases/91.json', '55651e3f209a4ed435d1b9ee6daa46cbfd843e9d13e438bd5e01c3dfe495472a', 97
    UNION ALL SELECT 92, 19, 'test-cases/92.json', 'd673a8897b7dd7d58f7d561de78ed108614d44c8e0699e325105689ca79f0505', 88
    UNION ALL SELECT 93, 19, 'test-cases/93.json', '6727feeb0a25c5eac025e42b31f32b271ccce2fa5d4eb0dcffd61f94d1a3232a', 79
    UNION ALL SELECT 94, 19, 'test-cases/94.json', '94c609ae74fb041820b39bd8c2342674257666f8d4e40563b8b3288638cdc5a8', 88
    UNION ALL SELECT 95, 19, 'test-cases/95.json', '0624357ed46d24204af7a12266b088f278b110ef0e87fc98bd81712461d33392', 103
    UNION ALL SELECT 96, 20, 'test-cases/96.json', 'b2e2433af89c65c42c139041e63deed616499553425c9a0f1a8178744e872916', 72
    UNION ALL SELECT 97, 20, 'test-cases/97.json', '6d4a63afc2d50490d61c5eb95fcd426886acc72b2485a39b4da8c80337cac74d', 66
    UNION ALL SELECT 98, 20, 'test-cases/98.json', '13cd48c46d021d55d82788d2b605c40d7fd31fbc6d8795e3050fb3c423de08aa', 69
    UNION ALL SELECT 99, 20, 'test-cases/99.json', '31a402df0fa5a368107cfedd7140a1ff2e6b81250d8604c348a42e4e213a79ad', 76
    UNION ALL SELECT 100, 20, 'test-cases/100.json', '31b448d8b9bb768b818cef868aa20909be130cc61e81b47e05fed136e8d36eee', 76
    UNION ALL SELECT 101, 21, 'test-cases/101.json', 'ccbc88c45f1e5189b9a6096b9baf619b5802ab3b23314fdd0249c655cae549e1', 103
    UNION ALL SELECT 102, 21, 'test-cases/102.json', 'd9be778a904e51992c02c801560fc729969cc23e3dc87dad56b7d45e40d7a5a9', 103
    UNION ALL SELECT 103, 21, 'test-cases/103.json', '2250ae9949af3fd144781ead5c7c81acb58d3f39b95421b2b001757945e6abfe', 91
    UNION ALL SELECT 104, 21, 'test-cases/104.json', 'ec966142a1ffaa094eb40588466e07369844821636311ddf843bbfd9e0c8a0a7', 103
    UNION ALL SELECT 105, 21, 'test-cases/105.json', '5a335b6c12959bce780e3c1d252400a966d316219006d587c829a045a8422279', 98
    UNION ALL SELECT 106, 22, 'test-cases/106.json', '9df37dc915a8bd1f61c4605e7296e5dedb0a0d9a68ef7bd0e3ee169bf76eea13', 65
    UNION ALL SELECT 107, 22, 'test-cases/107.json', 'dae6a3a2afbaec59ba6e53cd05c65d55df0ab765923ca359def00ec191292931', 65
    UNION ALL SELECT 108, 22, 'test-cases/108.json', '1334d3602d114ffd0b3fef1c05936a0d290f7d15bf88a42a6386950e02cac997', 65
    UNION ALL SELECT 109, 22, 'test-cases/109.json', '3004be289083e990d504282ff27d99a2041933a3b85fdc92800e2d7bb1f67da4', 65
    UNION ALL SELECT 110, 22, 'test-cases/110.json', '721d724a907b3c1feb42431cbf314448174aaa7d90b45152188487de32ebf1a9', 67
    UNION ALL SELECT 111, 23, 'test-cases/111.json', '4a1e56f9e811317b5a97015d988600c20282704e26c7e18d6054ab6f328a1797', 109
    UNION ALL SELECT 112, 23, 'test-cases/112.json', '2103aa1076e9fb762e3430d4facd9b62843f723b1be355873576f0645d575b32', 99
    UNION ALL SELECT 113, 23, 'test-cases/113.json', '69d40e4ea75f4781a4026fe38ae3d679e5add95a37c8ae856290ee93e2148411', 97
    UNION ALL SELECT 114, 23, 'test-cases/114.json', '5c6c42d2406889d67378dd3f8073718c6fdda239c9683d1a3cb486cbd990157b', 109
    UNION ALL SELECT 115, 23, 'test-cases/115.json', '4b2cb02f245c66b46c60ad60bd117f24dacc4c4bacbf5622c0aac6dd88ed6d53', 115
    UNION ALL SELECT 116, 24, 'test-cases/116.json', '40de33a49b63f31f28638b3efbd2c1d7485bba2ab3cad4820f1e6bc291db2f7f', 84
    UNION ALL SELECT 117, 24, 'test-cases/117.json', '3dc415cce54e31eb1e8a85463d45711b4d2e0e10bec7c029fe4d40c935a3e03f', 69
    UNION ALL SELECT 118, 24, 'test-cases/118.json', '890e85a6e01200bf30a9537505333508aebd259b5ad3601cdc1d222e68090f54', 75
    UNION ALL SELECT 119, 24, 'test-cases/119.json', '8baa9ee556c4e343a5cd3dfbfcf98d002d0e1128ad33a9cabbcedc855ad856c6', 75
    UNION ALL SELECT 120, 24, 'test-cases/120.json', 'e414f23011dfd0d0b8ddedf62dad0d07a2f8895e22ac8542e12731602852f85d', 78
    UNION ALL SELECT 121, 25, 'test-cases/121.json', '2d7c7a746d0f3702b08297eba2339736b4321ecc27c4d01d8e29078490e8d670', 106
    UNION ALL SELECT 122, 25, 'test-cases/122.json', '9ee6991283cd0413417209bbb7f8269f56add580fdf60cec1689d4d00224ef68', 97
    UNION ALL SELECT 123, 25, 'test-cases/123.json', '41bc855f03e15d3dffaf873d5ae544d83aaf3548162c351a4982c5b299b793bf', 91
    UNION ALL SELECT 124, 25, 'test-cases/124.json', '67a5b0c14c9953f0d38baae2d300729e8b6bbd29cecbe8d19ea354b9dc9f26e1', 99
    UNION ALL SELECT 125, 25, 'test-cases/125.json', 'd13fe915cb7a8f47728f63c31833bf5e81f3356504fcf8c3bceb175cdbe4e3a5', 104
    UNION ALL SELECT 126, 26, 'test-cases/126.json', '99471e8196dae6031dc3278af99d538c16354200445c54eef09c05de8adeafbf', 99
    UNION ALL SELECT 127, 26, 'test-cases/127.json', '14a4489bd30672cccb898dfc924d35caa5e83fee97f5dc879d6bca5843dd7c45', 96
    UNION ALL SELECT 128, 26, 'test-cases/128.json', '2ca335f8d41dadb3a5cd3325ade14c73babe0d2dd0b10485bdbd7e26d0e1c2b6', 92
    UNION ALL SELECT 129, 26, 'test-cases/129.json', '96d8f2d204de37c84a56e805fa5a446a2e0ccd9190471166778534bb5effe982', 102
    UNION ALL SELECT 130, 26, 'test-cases/130.json', 'ba2324e71c9b232f8b5d8d675601633c542b08b00af65b0e2d17c5800d619959', 103
    UNION ALL SELECT 131, 27, 'test-cases/131.json', '7d73a28991e7c92070c6c75bc58e47bb2800d4d1be585a3900f1785f56864ff5', 98
    UNION ALL SELECT 132, 27, 'test-cases/132.json', 'c43909e5c3a5441fc1da75e558f030d101d9dee9d5ef5aab9d0fcddb5140fd98', 97
    UNION ALL SELECT 133, 27, 'test-cases/133.json', '10c4d500a6cb214d9fe7a6283161cac5013540d8a276bfe483926a551ae98e81', 88
    UNION ALL SELECT 134, 27, 'test-cases/134.json', '73e20187c1516a68ad2d18d4b7c8a4026afb9c696ad4156c6b05dd185012e450', 91
    UNION ALL SELECT 135, 27, 'test-cases/135.json', 'dd2fd4632d0e6bd8b7e18d46418edb11aa2f254c8acc00a39e13c67729a84cfa', 97
    UNION ALL SELECT 136, 28, 'test-cases/136.json', '1e1a40c5a2801ed7536aa32be659f7b41ab741bdca87876b2e2df65876518b19', 105
    UNION ALL SELECT 137, 28, 'test-cases/137.json', '1c9bf1bf1487f53b0aa2e9d2caea563562f8cd3fc6c669ff8abd1db26d16fd66', 89
    UNION ALL SELECT 138, 28, 'test-cases/138.json', '8a03f7d7d466de7e0296ee6e52ce8e42cae4daf627b5d22e4a37512d8c999eb0', 83
    UNION ALL SELECT 139, 28, 'test-cases/139.json', '84e32e61f9500a2487d4630a712bbbd5404c35b2ec90b0bb2ff054ad4d145895', 83
    UNION ALL SELECT 140, 28, 'test-cases/140.json', '8c0f2d616c89316c208918b949e9407b5d4c48fc1afd2a438b9f96a5e7b766d0', 105
    UNION ALL SELECT 141, 29, 'test-cases/141.json', '01618aef0ca451b70d8a1d36b751068a91ee9a1ce3f81a5c9a574c3ee524e69a', 122
    UNION ALL SELECT 142, 29, 'test-cases/142.json', 'd2350ee424db295e0326f12bc577107a040bc2253bce8c3d4537a0d65b0a5d19', 111
    UNION ALL SELECT 143, 29, 'test-cases/143.json', 'b38816d10fb0950a5674996e63bcdae3561a2e8b044795707f8d4ca0b0b2dd07', 89
    UNION ALL SELECT 144, 29, 'test-cases/144.json', '6d6bde5bbbf2226bb7fae8b5bef594bdd5bb878cfd9f203e2d47f7884262944c', 111
    UNION ALL SELECT 145, 29, 'test-cases/145.json', '86d80e3ac81675acf4d11a3d23eb26031f5fc2c56249a18d15c5493230d8e376', 124
    UNION ALL SELECT 146, 30, 'test-cases/146.json', '1c89595bab3cd3aeb50c7381395b32159ed0b19f85dfcdee6310f228adcc51f0', 139
    UNION ALL SELECT 147, 30, 'test-cases/147.json', '7e831ff7166563987b6ddf59c599652ab4a643ec4b7ce3a1d9d66cfeda1e79ad', 116
    UNION ALL SELECT 148, 30, 'test-cases/148.json', '2fed22d3a70bd8b702e8936d60dbb67fcbec892ad580491b068a5f204edb584d', 105
    UNION ALL SELECT 149, 30, 'test-cases/149.json', '1bb3114958e69f0c8ca82424ed47e060b914b1f5515ca4300e991c4b4e25aa71', 127
    UNION ALL SELECT 150, 30, 'test-cases/150.json', '12ce1f144a1ed1111f944749ce0c8aa1182207646af02fbaf919828c38bfa253', 127
) t ON t.problem_seq = p.seq
ORDER BY t.seq;
