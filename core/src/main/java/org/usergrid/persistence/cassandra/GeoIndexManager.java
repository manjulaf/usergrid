/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.persistence.cassandra;

import static me.prettyprint.hector.api.factory.HFactory.createColumn;
import static me.prettyprint.hector.api.factory.HFactory.createMutator;
import static org.usergrid.persistence.Schema.DICTIONARY_LOCATIONS;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.batchExecute;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.key;
import static org.usergrid.utils.ClassUtils.cast;
import static org.usergrid.utils.ConversionUtils.bytebuffer;
import static org.usergrid.utils.UUIDUtils.getTimestampInMicros;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import me.prettyprint.cassandra.serializers.ByteBufferSerializer;
import me.prettyprint.cassandra.serializers.DoubleSerializer;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.serializers.UUIDSerializer;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.DynamicComposite;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.mutation.Mutator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usergrid.persistence.EntityRef;
import org.usergrid.persistence.Results;
import org.usergrid.persistence.Results.Level;
import org.usergrid.utils.UUIDUtils;

import com.beoui.geocell.GeocellManager;
import com.beoui.geocell.GeocellQueryEngine;
import com.beoui.geocell.annotations.Latitude;
import com.beoui.geocell.annotations.Longitude;
import com.beoui.geocell.model.GeocellQuery;
import com.beoui.geocell.model.Point;

public class GeoIndexManager {

	private static final Logger logger = LoggerFactory
			.getLogger(GeoIndexManager.class);

	public static class EntityLocationRef implements EntityRef {

		private UUID uuid;

		private String type;

		private UUID timestampUuid = UUIDUtils.newTimeUUID();

		@Latitude
		private double latitude;

		@Longitude
		private double longitude;

		public EntityLocationRef() {
		}

		public EntityLocationRef(EntityRef entity, double latitude,
				double longitude) {
			this(entity.getType(), entity.getUuid(), latitude, longitude);
		}

		public EntityLocationRef(String type, UUID uuid, double latitude,
				double longitude) {
			this.type = type;
			this.uuid = uuid;
			this.latitude = latitude;
			this.longitude = longitude;
		}

		public EntityLocationRef(EntityRef entity, UUID timestampUuid,
				double latitude, double longitude) {
			this(entity.getType(), entity.getUuid(), timestampUuid, latitude,
					longitude);
		}

		public EntityLocationRef(String type, UUID uuid, UUID timestampUuid,
				double latitude, double longitude) {
			this.type = type;
			this.uuid = uuid;
			this.timestampUuid = timestampUuid;
			this.latitude = latitude;
			this.longitude = longitude;
		}

		@Override
		public UUID getUuid() {
			return uuid;
		}

		public void setUuid(UUID uuid) {
			this.uuid = uuid;
		}

		@Override
		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public UUID getTimestampUuid() {
			return timestampUuid;
		}

		public void setTimestampUuid(UUID timestampUuid) {
			this.timestampUuid = timestampUuid;
		}

		public double getLatitude() {
			return latitude;
		}

		public void setLatitude(double latitude) {
			this.latitude = latitude;
		}

		public double getLongitude() {
			return longitude;
		}

		public void setLongitude(double longitude) {
			this.longitude = longitude;
		}

		public Point getPoint() {
			return new Point(latitude, longitude);
		}

	}

	EntityManagerImpl em;
	CassandraService cass;

	public GeoIndexManager() {
	}

	public GeoIndexManager init(EntityManagerImpl em) {
		this.em = em;
		cass = em.cass;
		return this;
	}

	public static List<EntityLocationRef> getLocationIndexEntries(
			List<HColumn<ByteBuffer, ByteBuffer>> columns) {
		List<EntityLocationRef> entries = new ArrayList<EntityLocationRef>();
		if (columns != null) {
			EntityLocationRef prevEntry = null;
			for (HColumn<ByteBuffer, ByteBuffer> column : columns) {
				DynamicComposite composite = DynamicComposite
						.fromByteBuffer(column.getName());
				UUID uuid = composite.get(0, UUIDSerializer.get());
				String type = composite.get(1, StringSerializer.get());
				UUID timestampUuid = composite.get(2, UUIDSerializer.get());
				composite = DynamicComposite.fromByteBuffer(column.getValue());
				Double latitude = composite.get(0, DoubleSerializer.get());
				Double longitude = composite.get(1, DoubleSerializer.get());
				if ((prevEntry != null) && uuid.equals(prevEntry.getUuid())) {
					prevEntry.setLatitude(latitude);
					prevEntry.setLongitude(longitude);
				} else {
					prevEntry = new EntityLocationRef(type, uuid,
							timestampUuid, latitude, longitude);
					entries.add(prevEntry);
				}
			}
		}
		return entries;
	}

	public static List<EntityLocationRef> mergeLocationEntries(
			List<EntityLocationRef> list, List<EntityLocationRef>... lists) {
		if ((lists == null) || (lists.length == 0)) {
			return list;
		}
		LinkedHashMap<UUID, EntityLocationRef> merge = new LinkedHashMap<UUID, EntityLocationRef>();
		for (EntityLocationRef loc : list) {
			merge.put(loc.getUuid(), loc);
		}
		for (List<EntityLocationRef> l : lists) {
			for (EntityLocationRef loc : l) {
				if (merge.containsKey(loc.getUuid())) {
					if (UUIDUtils.compare(loc.getTimestampUuid(),
							merge.get(loc.getUuid()).getTimestampUuid()) > 0) {
						merge.put(loc.getUuid(), loc);
					}
				} else {
					merge.put(loc.getUuid(), loc);
				}
			}
		}
		return new ArrayList<EntityLocationRef>(merge.values());
	}

	@SuppressWarnings("unchecked")
	public List<EntityLocationRef> query(Object key,
			List<String> curGeocellsUnique, UUID startResult, int count,
			boolean reversed) {

		List<EntityLocationRef> list = new ArrayList<EntityLocationRef>();

		for (String geoCell : curGeocellsUnique) {
			logger.info("Finding entities for cell: " + geoCell);
			List<HColumn<ByteBuffer, ByteBuffer>> columns;
			try {
				columns = cass.getColumns(
						cass.getApplicationKeyspace(em.applicationId),
						ApplicationCF.ENTITY_INDEX, key(key, geoCell),
						startResult, null, count, reversed);
				List<EntityLocationRef> entries = getLocationIndexEntries(columns);
				list = mergeLocationEntries(list, entries);
			} catch (Exception e) {
				e.printStackTrace();
			}

		}
		return list;
	}

	public Results proximitySearchCollection(final EntityRef headEntity,
			final String collectionName, final String propertyName,
			Point center, double maxDistance, final UUID startResult,
			final int count, final boolean reversed, Level level)
			throws Exception {

		GeocellQueryEngine gqe = new GeocellQueryEngine() {
			@SuppressWarnings("unchecked")
			@Override
			public <T> List<T> query(GeocellQuery baseQuery,
					List<String> curGeocellsUnique, Class<T> entityClass) {
				return (List<T>) GeoIndexManager.this.query(
						key(headEntity.getUuid(), DICTIONARY_LOCATIONS,
								collectionName, propertyName),
						curGeocellsUnique, startResult, count, reversed);
			}
		};

		return doSearch(center, maxDistance, gqe, count, level);
	}

	private Results doSearch(Point center, double maxDistance,
			GeocellQueryEngine gqe, int count, Level level) throws Exception {
		List<EntityLocationRef> locations = null;

		GeocellQuery baseQuery = new GeocellQuery();
		try {
			locations = GeocellManager.proximitySearch(center, count,
					maxDistance, EntityLocationRef.class, baseQuery, gqe,
					GeocellManager.MAX_GEOCELL_RESOLUTION);
		} catch (Exception e) {
			e.printStackTrace();
		}

		@SuppressWarnings("unchecked")
		Results results = Results
				.fromRefList((List<EntityRef>) cast(locations));
		results = em.loadEntities(results, level, count);
		return results;
	}

	public static Mutator<ByteBuffer> addLocationEntryToMutator(
			Mutator<ByteBuffer> m, Object key, EntityLocationRef entry) {

		HColumn<ByteBuffer, ByteBuffer> column = createColumn(
				DynamicComposite.toByteBuffer(entry.getUuid(), entry.getType(),
						entry.getTimestampUuid()),
				DynamicComposite.toByteBuffer(entry.getLatitude(),
						entry.getLongitude()),
				getTimestampInMicros(entry.getTimestampUuid()),
				ByteBufferSerializer.get(), ByteBufferSerializer.get());
		m.addInsertion(bytebuffer(key), ApplicationCF.ENTITY_INDEX.toString(),
				column);

		return m;
	}

	public void storeLocation(EntityRef headEntity, String collectionName,
			String propertyName, EntityLocationRef location) {

		Point p = location.getPoint();
		List<String> cells = GeocellManager.generateGeoCell(p);

		Keyspace ko = cass.getApplicationKeyspace(em.applicationId);
		Mutator<ByteBuffer> m = createMutator(ko, ByteBufferSerializer.get());

		Object key = key(headEntity.getUuid(), DICTIONARY_LOCATIONS,
				collectionName, propertyName);

		for (String cell : cells) {
			addLocationEntryToMutator(m, key(key, cell), location);
		}

		batchExecute(m, CassandraService.RETRY_COUNT);

		logger.info("Geocells to be saved for Point(" + location.latitude + ","
				+ location.longitude + ") are: " + cells);
	}

}
