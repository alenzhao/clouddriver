/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.oort.aws.controllers

import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.oort.aws.data.Keys
import groovy.transform.InheritConstructors
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

import static com.netflix.spinnaker.oort.aws.data.Keys.Namespace.IMAGES
import static com.netflix.spinnaker.oort.aws.data.Keys.Namespace.NAMED_IMAGES

@RestController
@RequestMapping("/aws/images")
class AmazonNamedImageLookupController {
  private static final int MIN_NAME_FILTER = 2;
  private static final String EXCEPTION_REASON = 'Minimum of ' + MIN_NAME_FILTER + ' characters required to filter namedImages'

  private final Cache cacheView

  @Autowired
  AmazonNamedImageLookupController(Cache cacheView) {
    this.cacheView = cacheView
  }

  @RequestMapping(value = '/{account}/{region}/{imageId:.+}', method = RequestMethod.GET)
  List<NamedImage> getByAmiId(@PathVariable String account, @PathVariable String region, @PathVariable String imageId) {
    CacheData cd = cacheView.get(IMAGES.ns, Keys.getImageKey(imageId, account, region))
    if (cd == null) {
      throw new ImageNotFoundException("${imageId} not found in ${account}/${region}")
    }
    Collection<String> namedImageKeys = cd.relationships[NAMED_IMAGES.ns]

    if (!namedImageKeys) {
      throw new ImageNotFoundException("Name not found on image ${imageId} in ${account}/${region}")
    }

    Collection<CacheData> namedImages = cacheView.getAll(NAMED_IMAGES.ns, namedImageKeys)
    render(namedImages, null, region)
  }

  @RequestMapping(value = '/find', method = RequestMethod.GET)
  List<NamedImage> list(LookupOptions lookupOptions) {
    if (lookupOptions.q == null || lookupOptions.q.length() < MIN_NAME_FILTER) {
      throw new InsufficientLookupOptionsException(EXCEPTION_REASON)
    }

    String glob = lookupOptions.q
    // Wrap in '*' if there are no glob-style characters in the query string
    if (!glob.contains('*') && !glob.contains('?') && !glob.contains('[') && !glob.contains('\\')) {
      glob = "*${glob}*"
    }

    def namedImageSearch = Keys.getNamedImageKey(lookupOptions.account ?: '*', glob)
    def imageSearch = Keys.getImageKey(glob, lookupOptions.account ?: '*', lookupOptions.region ?: '*')

    Collection<String> namedImageIdentifiers = cacheView.filterIdentifiers(NAMED_IMAGES.ns, namedImageSearch)
    Collection<String> imageIdentifiers = cacheView.filterIdentifiers(IMAGES.ns, imageSearch)

    Collection<CacheData> matchesByName = cacheView.getAll(NAMED_IMAGES.ns, namedImageIdentifiers)

    Collection<CacheData> matchesByImageId = cacheView.getAll(IMAGES.ns, imageIdentifiers)

    render(matchesByName, matchesByImageId, lookupOptions.q, lookupOptions.region)
  }

  private List<NamedImage> render(Collection<CacheData> namedImages, Collection<CacheData> images, String requestedName = null, String requiredRegion = null) {
    if (!namedImages && !images) {
      throw new ImageNotFoundException('Not found')
    }
    Map<String, NamedImage> byImageName = [:].withDefault { new NamedImage(imageName: it) }
    for (CacheData data : namedImages) {
      Map<String, String> keyParts = Keys.parse(data.id)
      NamedImage thisImage = byImageName[keyParts.imageName]
      thisImage.accounts.add(keyParts.account)
      for (String imageKey : data.relationships[IMAGES.ns] ?: []) {
        Map<String, String> imageParts = Keys.parse(imageKey)
        thisImage.amis[imageParts.region].add(imageParts.imageId)
      }
    }

    for (CacheData data : images ) {
      Map<String, String> amiKeyParts = Keys.parse(data.id)
      Map<String, String> namedImageKeyParts = Keys.parse(data.relationships[NAMED_IMAGES.ns][0])
      NamedImage thisImage = byImageName[namedImageKeyParts.imageName]
      thisImage.accounts.add(namedImageKeyParts.account)
      thisImage.amis[amiKeyParts.region].add(amiKeyParts.imageId)
    }

    List<NamedImage> results = byImageName.values().findAll { requiredRegion ? it.amis.containsKey(requiredRegion) : true }
    if (!results) {
      throw new ImageNotFoundException('Not found')
    }
    results.sort { a, b ->
      int a1, b1
      if (requestedName) {
        a1 = a.imageName.indexOf(requestedName)
        b1 = b.imageName.indexOf(requestedName)
      } else {
        a1 = 0
        b1 = 0
      }

      if (a1 == b1) {
        a.imageName <=> b.imageName
      } else {
        a1 <=> b1
      }
    }
  }


  @ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = 'Minimum of 2 characters required to filter namedImages')
  @InheritConstructors
  private static class InsufficientLookupOptionsException extends RuntimeException { }

  @ResponseStatus(value = HttpStatus.NOT_FOUND, reason = 'Image not found')
  @InheritConstructors
  private static class ImageNotFoundException extends RuntimeException { }


  private static class NamedImage {
    String imageName
    Set<String> accounts = []
    Map<String, Collection<String>> amis = [:].withDefault { new HashSet<String>() }
  }

  private static class LookupOptions {
    String q
    String account
    String region
  }
}
