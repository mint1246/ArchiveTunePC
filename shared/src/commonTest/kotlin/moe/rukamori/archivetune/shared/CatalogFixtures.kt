package moe.rukamori.archivetune.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Synthetic data only. URLs below are inert fixture values, never real signed stream URLs. */
internal object CatalogFixtures {
    const val CLICKED = "aa11bb22cc3"
    const val SECOND = "dd44ee55ff6"
    const val WRONG = "other123456"
    const val AUDIO_LOW = "https://r1---sn-test.googlevideo.com/videoplayback?itag=140&quality=low"
    const val AUDIO_HIGH = "https://r2---sn-test.googlevideo.com/videoplayback?itag=140&quality=high"
    const val HLS = "https://manifest.googlevideo.com/api/manifest/hls_playlist/test"

    val tabbedSearch = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicShelfRenderer": {
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": {"videoId": "aa11bb22cc3"},
                                  "flexColumns": [
                                    {"musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {"runs": [{"text": "First Song"}]}
                                    }},
                                    {"musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {"runs": [
                                        {"text": "Song"}, {"text": " • "},
                                        {"text": "Artist One"}, {"text": " • "},
                                        {"text": "3:45"}
                                      ]}
                                    }}
                                  ],
                                  "fixedColumns": [{
                                    "musicResponsiveListItemFixedColumnRenderer": {
                                      "text": {"runs": [{"text": "3:45"}]}
                                    }
                                  }],
                                  "thumbnail": {"musicThumbnailRenderer": {
                                    "thumbnail": {"thumbnails": [
                                      {"url": "https://i.ytimg.com/vi/aa11bb22cc3/hqdefault.jpg"}
                                    ]}
                                  }}
                                }
                              },
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": {"videoId": "aa11bb22cc3"},
                                  "flexColumns": [{
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {"simpleText": "Duplicate"}
                                    }
                                  }]
                                }
                              },
                              {
                                "musicResponsiveListItemRenderer": {
                                  "navigationEndpoint": {"browseEndpoint": {"browseId": "UC123"}},
                                  "flexColumns": [{
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {"simpleText": "Artist result"}
                                    }
                                  }]
                                }
                              }
                            ]
                          }
                        },
                        {
                          "musicCarouselShelfRenderer": {
                            "contents": [{
                              "musicTwoRowItemRenderer": {
                                "title": {"runs": [{"text": "Other Song"}]},
                                "subtitle": {"runs": [{"text": "Second Artist"}]},
                                "navigationEndpoint": {
                                  "watchEndpoint": {"videoId": "dd44ee55ff6"}
                                },
                                "thumbnailRenderer": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": {"thumbnails": [
                                      {"url": "https://i.ytimg.com/vi/dd44ee55ff6/mqdefault.jpg"}
                                    ]}
                                  }
                                }
                              }
                            }]
                          }
                        }
                      ]
                    }
                  }
                }
              }]
            }
          }
        }
    """.trimIndent()

    val alternateSearch = """
        {
          "continuationContents": {
            "musicShelfContinuation": {
              "contents": [{
                "musicResponsiveListItemRenderer": {
                  "playlistItemData": null,
                  "overlay": {
                    "musicItemThumbnailOverlayRenderer": {
                      "content": {
                        "musicPlayButtonRenderer": {
                          "playNavigationEndpoint": {
                            "watchEndpoint": {"videoId": "aa11bb22cc3"}
                          }
                        }
                      }
                    }
                  },
                  "flexColumns": [{
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": {"simpleText": "Alternate Song"}
                    }
                  }],
                  "fixedColumns": null
                }
              }, {
                "musicCardShelfRenderer": {
                  "title": {"runs": [{"text": "Featured Song"}]},
                  "subtitle": {"runs": [{"text": "Featured Artist"}]},
                  "onTap": {
                    "watchEndpoint": {"videoId": "dd44ee55ff6"}
                  }
                }
              }]
            }
          }
        }
    """.trimIndent()

    val playableFormats = """
        [
          {"itag": 251, "mimeType": "audio/webm; codecs=\"opus\"",
           "bitrate": 256000,
           "url": "https://r1---sn-test.googlevideo.com/videoplayback?itag=251"},
          {"itag": 18, "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
           "bitrate": 500000,
           "url": "https://r1---sn-test.googlevideo.com/videoplayback?itag=18"},
          {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
           "bitrate": 128000,
           "url": "https://r1---sn-test.googlevideo.com/videoplayback?itag=140&quality=low"},
          {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
           "bitrate": 192000,
           "url": "https://r2---sn-test.googlevideo.com/videoplayback?itag=140&quality=high"}
        ]
    """.trimIndent()

    val cipherOnly = """
        [
          {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
           "signatureCipher": "s=encryptedValue&sp=sig&url=encoded"}
        ]
    """.trimIndent()

    val unsupportedFormats = """
        [
          {"itag": 251, "mimeType": "audio/webm; codecs=\"opus\"",
           "url": "https://r1---sn-test.googlevideo.com/videoplayback?itag=251"},
          {"itag": 141, "mimeType": "audio/mp4; codecs=\"alac\"",
           "url": "https://r1---sn-test.googlevideo.com/videoplayback?itag=141"}
        ]
    """.trimIndent()

    fun player(
        videoId: String = CLICKED,
        formats: String = playableFormats,
        status: String = "OK",
        reason: String? = null,
        hls: String? = null,
    ): String = buildJsonObject {
        put("playabilityStatus", buildJsonObject {
            put("status", status)
            if (reason != null) put("reason", reason)
        })
        put("videoDetails", buildJsonObject { put("videoId", videoId) })
        put("streamingData", buildJsonObject {
            put("adaptiveFormats", Json.parseToJsonElement(formats))
            if (hls != null) put("hlsManifestUrl", hls)
        })
    }.toString()

    fun asJson(text: String): JsonElement = Json.parseToJsonElement(text)
}
