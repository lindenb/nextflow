/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Task Execution Service
 *
 * OpenAPI spec version: 1.1.0
 * 
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */

package nextflow.ga4gh.tes.client.model;

import java.util.Objects;
import java.util.Arrays;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import nextflow.ga4gh.tes.client.model.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
/**
 * TesServiceType
 */

@javax.annotation.Generated(value = "io.swagger.codegen.v3.generators.java.JavaClientCodegen", date = "2023-08-15T14:10:09.878Z[GMT]")

public class TesServiceType extends ServiceType {
  /**
   * Gets or Sets tesServiceTypeArtifact
   */
  @JsonAdapter(ArtifactEnum.Adapter.class)
  public enum ArtifactEnum {
    @SerializedName("tes")
    TES("tes");

    private String value;

    ArtifactEnum(String value) {
      this.value = value;
    }
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }
    public static ArtifactEnum fromValue(String input) {
      for (ArtifactEnum b : ArtifactEnum.values()) {
        if (b.value.equals(input)) {
          return b;
        }
      }
      return null;
    }
    public static class Adapter extends TypeAdapter<ArtifactEnum> {
      @Override
      public void write(final JsonWriter jsonWriter, final ArtifactEnum enumeration) throws IOException {
        jsonWriter.value(String.valueOf(enumeration.getValue()));
      }

      @Override
      public ArtifactEnum read(final JsonReader jsonReader) throws IOException {
        Object value = jsonReader.nextString();
        return ArtifactEnum.fromValue((String)(value));
      }
    }
  }  @SerializedName("artifact")
  private ArtifactEnum tesServiceTypeArtifact = null;

  public TesServiceType tesServiceTypeArtifact(ArtifactEnum tesServiceTypeArtifact) {
    this.tesServiceTypeArtifact = tesServiceTypeArtifact;
    return this;
  }

   /**
   * Get tesServiceTypeArtifact
   * @return tesServiceTypeArtifact
  **/
  @Schema(example = "tes", required = true, description = "")
  public ArtifactEnum getTesServiceTypeArtifact() {
    return tesServiceTypeArtifact;
  }

  public void setTesServiceTypeArtifact(ArtifactEnum tesServiceTypeArtifact) {
    this.tesServiceTypeArtifact = tesServiceTypeArtifact;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TesServiceType tesServiceType = (TesServiceType) o;
    return Objects.equals(this.tesServiceTypeArtifact, tesServiceType.tesServiceTypeArtifact) &&
        super.equals(o);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tesServiceTypeArtifact, super.hashCode());
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TesServiceType {\n");
    sb.append("    ").append(toIndentedString(super.toString())).append("\n");
    sb.append("    tesServiceTypeArtifact: ").append(toIndentedString(tesServiceTypeArtifact)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}
