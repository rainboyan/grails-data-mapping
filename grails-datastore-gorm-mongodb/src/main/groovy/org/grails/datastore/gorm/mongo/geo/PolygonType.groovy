/* Copyright (C) 2014 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.gorm.mongo.geo

import grails.mongodb.geo.Point
import grails.mongodb.geo.Polygon
import groovy.transform.CompileStatic
import org.springframework.dao.DataAccessResourceFailureException

/**
 * Adds support for the {@link Polygon} type to GORM for MongoDB
 *
 * @author Graeme Rocher
 * @since 1.4
 */
@CompileStatic
class PolygonType extends GeoJSONType<Polygon> {

    PolygonType() {
        super(Polygon)
    }

    @Override
    Polygon createFromCoords(List coords) {
        if(coords.size() < 4) throw new DataAccessResourceFailureException("Invalid polygon data returned: $coords")

        def x = Point.fromList((List)coords.get(0))
        def y = Point.fromList((List)coords.get(1))
        def z = Point.fromList((List)coords.get(2))
        def remaining = coords.subList(3, coords.size())
        return new Polygon(x,y,z, remaining as Point[])
    }
}